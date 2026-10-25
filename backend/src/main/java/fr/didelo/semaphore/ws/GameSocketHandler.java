package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Player;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.RoomView;
import fr.didelo.semaphore.game.Unavailable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * One connection per player or spectator. A change loads the room, applies the command and saves it
 * ({@link RoomRegistry}); what goes back to the players is built from the room that was saved and
 * sent afterwards, so a slow connection never holds a room. Each view carries the room's version,
 * so a client drops one that arrives late. The answer to a message carries the message's id, and
 * what goes to the other players carries none.
 */
@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler {

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;
  // A friend mistypes a code once or twice; five unknown codes on one connection is a scan.
  private static final int MAX_MISSES = 5;

  /**
   * Where a connection is: the room it joined and the player it speaks for, none for a spectator.
   */
  private record Seat(String code, String playerId) {

    boolean spectator() {
      return playerId == null;
    }
  }

  /** A message to send once the room is saved. */
  private record Delivery(WebSocketSession session, ServerMessage message) {}

  private final JsonMapper json;
  private final RoomRegistry rooms;

  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, Seat> seats = new ConcurrentHashMap<>();
  private final Map<String, Integer> misses = new ConcurrentHashMap<>();

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
    // The connection closed, a limit hit here included, while this message was still arriving.
    if (session == null) {
      return;
    }
    // Stays null for a message too broken to read: there is no id to answer it with.
    String id = null;
    try {
      var incoming = json.readValue(message.getPayload(), ClientMessage.class);
      id = incoming.id();
      deliver(
          switch (incoming) {
            case ClientMessage.CreateRoom create -> create(session, id, create.name());
            case ClientMessage.JoinRoom join ->
                enter(session, id, lookUp(session, join.code()), join.name());
            case ClientMessage.Rejoin rejoin -> rejoin(session, rejoin);
            case ClientMessage.Watch watch -> watch(session, id, lookUp(session, watch.code()));
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
    } catch (IllegalMove | Unavailable e) {
      deliver(List.of(new Delivery(session, new ServerMessage.Error(id, e.getMessage()))));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
    sessions.remove(raw.getId());
    misses.remove(raw.getId());
    // A connection replaced by a newer one of the same player has no seat left: nothing to undo.
    var seat = seats.remove(raw.getId());
    if (seat != null) {
      try {
        var closed =
            rooms.change(
                seat.code(),
                room -> {
                  if (seat.spectator()) {
                    room.unwatch();
                  } else {
                    room.disconnect(seat.playerId());
                  }
                });
        deliver(views(closed.room(), null, null));
      } catch (IllegalMove e) {
        // The room was dropped while spectators watched: nobody is left to tell.
      }
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
    requireToEnter(session);
    var created = rooms.create(name);
    return welcome(session, id, created.room(), created.result());
  }

  private List<Delivery> enter(WebSocketSession session, String id, Room found, String name) {
    requireToEnter(session);
    var joined = rooms.update(found.code(), room -> room.join(name));
    return welcome(session, id, joined.room(), joined.result());
  }

  private List<Delivery> watch(WebSocketSession session, String id, Room found) {
    requireToEnter(session);
    var watched = rooms.change(found.code(), Room::watch);
    seats.put(session.getId(), new Seat(found.code(), null));
    return views(watched.room(), session.getId(), id);
  }

  private List<Delivery> rejoin(WebSocketSession session, ClientMessage.Rejoin rejoin) {
    var found = lookUp(session, rejoin.code());
    requireToEnter(session);
    var known = found.byToken(rejoin.token()).orElseThrow(() -> new IllegalMove("unknown player"));
    // Out of the way before the change is saved, so the old connection closing cannot mark absent
    // a player who is back.
    var replaced = takeSeats(found.code(), known.getId());
    var rejoined =
        rooms.update(
            found.code(),
            room -> {
              var player =
                  room.byToken(rejoin.token()).orElseThrow(() -> new IllegalMove("unknown player"));
              room.reconnect(player.getId());
              return player;
            });
    var deliveries = welcome(session, rejoin.id(), rejoined.room(), rejoined.result());
    replaced.forEach(
        old -> close(old, CloseStatus.NORMAL.withReason("replaced by a newer connection")));
    return deliveries;
  }

  private List<Delivery> leave(WebSocketSession session) {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    var left =
        rooms.change(
            seat.code(),
            room -> {
              // The connection may have closed while this message was being handled.
              if (!seat.equals(seats.get(session.getId()))) {
                throw new IllegalMove("join a room first");
              }
              if (seat.spectator()) {
                room.unwatch();
              } else {
                room.leave(seat.playerId());
              }
            });
    seats.remove(session.getId(), seat);
    return views(left.room(), null, null);
  }

  private List<Delivery> command(WebSocketSession session, String id, Command command) {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    if (seat.spectator()) {
      throw new IllegalMove("spectators cannot play");
    }
    var done =
        rooms.change(
            seat.code(),
            room -> {
              // The connection may have closed while this message was being handled.
              if (!seat.equals(seats.get(session.getId()))) {
                throw new IllegalMove("join a room first");
              }
              room.apply(seat.playerId(), command);
            });
    return views(done.room(), session.getId(), id);
  }

  /** Counts the codes that find no room on a connection: the last one closes it. */
  private Room lookUp(WebSocketSession session, String code) {
    var room = rooms.find(code);
    if (room.isEmpty() && misses.merge(session.getId(), 1, Integer::sum) >= MAX_MISSES) {
      close(session, CloseStatus.POLICY_VIOLATION.withReason("too many unknown rooms"));
    }
    return room.orElseThrow(() -> new IllegalMove("no such room"));
  }

  private void close(WebSocketSession session, CloseStatus status) {
    try {
      session.close(status);
    } catch (IOException e) {
      // already gone
    }
  }

  private void requireToEnter(WebSocketSession session) {
    if (seats.containsKey(session.getId())) {
      throw new IllegalMove("you are already in a room");
    }
  }

  /** Removes the seats a player holds here and returns their connections. */
  private List<WebSocketSession> takeSeats(String code, String playerId) {
    var taken = new ArrayList<WebSocketSession>();
    seats
        .entrySet()
        .removeIf(
            entry -> {
              var same =
                  entry.getValue().code().equals(code)
                      && playerId.equals(entry.getValue().playerId());
              if (same) {
                taken.add(sessions.get(entry.getKey()));
              }
              return same;
            });
    return taken.stream().filter(Objects::nonNull).toList();
  }

  private List<Delivery> welcome(WebSocketSession session, String id, Room room, Player player) {
    seats.put(session.getId(), new Seat(room.code(), player.getId()));
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
      if (!seat.code().equals(room.code()) || session == null) {
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
  private void sweep(Consumer<Room> change) {
    for (var code : rooms.codes()) {
      try {
        var swept = rooms.change(code, change);
        if (swept.changed()) {
          deliver(views(swept.room(), null, null));
        }
      } catch (IllegalMove e) {
        // The room was dropped since the list was made.
      }
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
