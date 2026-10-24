package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Player;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.RoomView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * One connection per player. What changes a room happens under the room's lock; what goes back to
 * the players is built there and sent after the lock is released, so a slow connection never holds
 * a room. Each view carries the room's version, so a client drops one that arrives late. The answer
 * to a message carries the message's id, and what goes to the other players carries none.
 */
@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler {

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

  /**
   * Where a connection is: the room it joined and the player it speaks for, none for a spectator.
   */
  private record Seat(Room room, String playerId) {

    boolean spectator() {
      return playerId == null;
    }
  }

  /** A message waiting for the room's lock to be released. */
  private record Delivery(WebSocketSession session, ServerMessage message) {}

  private final JsonMapper json;
  private final RoomRegistry rooms;

  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, Seat> seats = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    sessions.put(
        session.getId(),
        new ConcurrentWebSocketSessionDecorator(
            session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
  }

  @Override
  protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
    var session = sessions.get(raw.getId());
    // Stays null for a message too broken to read: there is no id to answer it with.
    String id = null;
    try {
      var incoming = json.readValue(message.getPayload(), ClientMessage.class);
      id = incoming.id();
      deliver(
          switch (incoming) {
            case ClientMessage.CreateRoom create -> create(session, id, create.name());
            case ClientMessage.JoinRoom join -> enter(session, id, room(join.code()), join.name());
            case ClientMessage.Rejoin rejoin -> rejoin(session, rejoin);
            case ClientMessage.Watch watch -> watch(session, id, room(watch.code()));
            case ClientMessage.Leave leave -> leave(session);
            case ClientMessage.JoinTeam join ->
                command(session, id, new Command.JoinTeam(join.team()));
            case ClientMessage.Start start -> command(session, id, new Command.Start());
            case ClientMessage.Signal signal ->
                command(session, id, new Command.Signal(signal.word(), signal.number()));
            case ClientMessage.Reveal reveal ->
                command(session, id, new Command.Reveal(reveal.index()));
            case ClientMessage.Pass pass -> command(session, id, new Command.Pass());
          });
    } catch (JacksonException e) {
      deliver(List.of(new Delivery(session, new ServerMessage.Error(id, "unreadable message"))));
    } catch (IllegalMove e) {
      deliver(List.of(new Delivery(session, new ServerMessage.Error(id, e.getMessage()))));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
    sessions.remove(raw.getId());
    // A connection replaced by a newer one of the same player has no seat left: nothing to undo.
    var seat = seats.remove(raw.getId());
    if (seat != null) {
      List<Delivery> deliveries;
      synchronized (seat.room()) {
        if (seat.spectator()) {
          seat.room().unwatch();
        } else {
          seat.room().disconnect(seat.playerId());
        }
        deliveries = views(seat.room(), null, null);
      }
      deliver(deliveries);
    }
  }

  /** The lobby keeps an absent player for a while: once it is over, the others see them go. */
  @Scheduled(fixedDelay = 5_000)
  void dropAbsent() {
    sweep(Room::dropAbsent);
  }

  /** The turn passes, or the game ends, when the game has waited long enough for someone away. */
  @Scheduled(fixedDelay = 1_000)
  void expire() {
    sweep(Room::expire);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    session.close(CloseStatus.SERVER_ERROR);
  }

  private List<Delivery> create(WebSocketSession session, String id, String name) {
    var room = rooms.create();
    try {
      return enter(session, id, room, name);
    } catch (IllegalMove e) {
      rooms.drop(room);
      throw e;
    }
  }

  private List<Delivery> enter(WebSocketSession session, String id, Room room, String name) {
    synchronized (room) {
      requireToEnter(session, room);
      return welcome(session, id, room, room.join(name));
    }
  }

  private List<Delivery> watch(WebSocketSession session, String id, Room room) {
    synchronized (room) {
      requireToEnter(session, room);
      room.watch();
      seats.put(session.getId(), new Seat(room, null));
      return views(room, session.getId(), id);
    }
  }

  private List<Delivery> rejoin(WebSocketSession session, ClientMessage.Rejoin rejoin) {
    var room = room(rejoin.code());
    var replaced = new ArrayList<WebSocketSession>();
    List<Delivery> deliveries;
    synchronized (room) {
      requireToEnter(session, room);
      var player =
          room.byToken(rejoin.token()).orElseThrow(() -> new IllegalMove("unknown player"));
      seats
          .entrySet()
          .removeIf(
              entry -> {
                var same =
                    entry.getValue().room() == room
                        && player.getId().equals(entry.getValue().playerId());
                if (same) {
                  replaced.add(sessions.get(entry.getKey()));
                }
                return same;
              });
      room.reconnect(player.getId());
      deliveries = welcome(session, rejoin.id(), room, player);
    }
    replaced.stream()
        .filter(Objects::nonNull)
        .forEach(
            old -> {
              try {
                old.close(CloseStatus.NORMAL.withReason("replaced by a newer connection"));
              } catch (IOException e) {
                // already gone
              }
            });
    return deliveries;
  }

  private List<Delivery> leave(WebSocketSession session) {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    synchronized (seat.room()) {
      // The connection may have closed while this message waited for the lock.
      if (!seats.remove(session.getId(), seat)) {
        throw new IllegalMove("join a room first");
      }
      if (seat.spectator()) {
        seat.room().unwatch();
      } else {
        seat.room().leave(seat.playerId());
      }
      return views(seat.room(), null, null);
    }
  }

  private List<Delivery> command(WebSocketSession session, String id, Command command) {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    if (seat.spectator()) {
      throw new IllegalMove("spectators cannot play");
    }
    synchronized (seat.room()) {
      // The connection may have closed while this message waited for the lock.
      if (!seat.equals(seats.get(session.getId()))) {
        throw new IllegalMove("join a room first");
      }
      seat.room().apply(seat.playerId(), command);
      return views(seat.room(), session.getId(), id);
    }
  }

  private Room room(String code) {
    return rooms.find(code).orElseThrow(() -> new IllegalMove("no such room"));
  }

  /**
   * Called under the room's lock. The room may have been swept since it was found: a player must
   * not end up in a room nobody can find.
   */
  private void requireToEnter(WebSocketSession session, Room room) {
    if (seats.containsKey(session.getId())) {
      throw new IllegalMove("you are already in a room");
    }
    if (rooms.find(room.code()).filter(found -> found == room).isEmpty()) {
      throw new IllegalMove("no such room");
    }
  }

  private List<Delivery> welcome(WebSocketSession session, String id, Room room, Player player) {
    seats.put(session.getId(), new Seat(room, player.getId()));
    var deliveries = new ArrayList<Delivery>();
    deliveries.add(
        new Delivery(
            session,
            new ServerMessage.Joined(
                id, player.getId(), player.getToken(), room.viewFor(player.getId()))));
    deliveries.addAll(views(room, null, null));
    return deliveries;
  }

  /**
   * Every connected player of the room gets their own view: only the Signaleurs see the hidden
   * cards. The spectators all get the same one, built once. The view of the connection that sent
   * the message carries its id, the others none.
   */
  private List<Delivery> views(Room room, String senderSessionId, String id) {
    var deliveries = new ArrayList<Delivery>();
    RoomView spectatorView = null;
    for (var entry : seats.entrySet()) {
      var seat = entry.getValue();
      var session = sessions.get(entry.getKey());
      if (seat.room() != room || session == null) {
        continue;
      }
      RoomView view;
      if (seat.spectator()) {
        spectatorView = spectatorView == null ? room.spectatorView() : spectatorView;
        view = spectatorView;
      } else {
        view = room.viewFor(seat.playerId());
      }
      deliveries.add(
          new Delivery(
              session,
              new ServerMessage.RoomUpdated(
                  entry.getKey().equals(senderSessionId) ? id : null, view)));
    }
    return deliveries;
  }

  /** Runs a sweep on every room and sends the new views of the ones it changed. */
  private void sweep(Predicate<Room> changesRoom) {
    for (var room : rooms.all()) {
      List<Delivery> deliveries = List.of();
      synchronized (room) {
        if (changesRoom.test(room)) {
          deliveries = views(room, null, null);
        }
      }
      deliver(deliveries);
    }
  }

  private void deliver(List<Delivery> deliveries) {
    for (var delivery : deliveries) {
      try {
        delivery
            .session()
            .sendMessage(new TextMessage(json.writeValueAsString(delivery.message())));
      } catch (Exception e) {
        // A connection that breaks, or whose buffer overflows, is closed by the container: it must
        // not stop the others from getting the update.
      }
    }
  }
}
