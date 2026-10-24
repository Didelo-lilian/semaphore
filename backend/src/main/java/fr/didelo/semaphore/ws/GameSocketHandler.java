package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Player;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
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
 * a room. Each view carries the room's version, so a client drops one that arrives late.
 */
@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler {

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

  /** Where a connection is: the room it joined and the player it speaks for. */
  private record Seat(Room room, String playerId) {}

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
    try {
      var incoming = json.readValue(message.getPayload(), ClientMessage.class);
      deliver(
          switch (incoming) {
            case ClientMessage.CreateRoom create -> create(session, create.name());
            case ClientMessage.JoinRoom join -> enter(session, room(join.code()), join.name());
            case ClientMessage.Rejoin rejoin -> rejoin(session, rejoin);
            case ClientMessage.JoinTeam join -> command(session, new Command.JoinTeam(join.team()));
            case ClientMessage.Start start -> command(session, new Command.Start());
            case ClientMessage.Signal signal ->
                command(session, new Command.Signal(signal.word(), signal.number()));
            case ClientMessage.Reveal reveal ->
                command(session, new Command.Reveal(reveal.index()));
            case ClientMessage.Pass pass -> command(session, new Command.Pass());
          });
    } catch (JacksonException e) {
      deliver(List.of(new Delivery(session, new ServerMessage.Error("unreadable message"))));
    } catch (IllegalMove e) {
      deliver(List.of(new Delivery(session, new ServerMessage.Error(e.getMessage()))));
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
        seat.room().disconnect(seat.playerId());
        deliveries = views(seat.room());
      }
      deliver(deliveries);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    session.close(CloseStatus.SERVER_ERROR);
  }

  private List<Delivery> create(WebSocketSession session, String name) {
    var room = rooms.create();
    try {
      return enter(session, room, name);
    } catch (IllegalMove e) {
      rooms.drop(room);
      throw e;
    }
  }

  private List<Delivery> enter(WebSocketSession session, Room room, String name) {
    synchronized (room) {
      requireToEnter(session, room);
      return welcome(session, room, room.join(name));
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
                        && entry.getValue().playerId().equals(player.getId());
                if (same) {
                  replaced.add(sessions.get(entry.getKey()));
                }
                return same;
              });
      room.reconnect(player.getId());
      deliveries = welcome(session, room, player);
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

  private List<Delivery> command(WebSocketSession session, Command command) {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    synchronized (seat.room()) {
      // The connection may have closed while this message waited for the lock.
      if (!seat.equals(seats.get(session.getId()))) {
        throw new IllegalMove("join a room first");
      }
      seat.room().apply(seat.playerId(), command);
      return views(seat.room());
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

  private List<Delivery> welcome(WebSocketSession session, Room room, Player player) {
    seats.put(session.getId(), new Seat(room, player.getId()));
    var deliveries = new ArrayList<Delivery>();
    deliveries.add(
        new Delivery(
            session,
            new ServerMessage.Joined(
                player.getId(), player.getToken(), room.viewFor(player.getId()))));
    deliveries.addAll(views(room));
    return deliveries;
  }

  /**
   * Every connected player of the room gets their own view: only the Signaleurs see the hidden
   * cards.
   */
  private List<Delivery> views(Room room) {
    return seats.entrySet().stream()
        .filter(entry -> entry.getValue().room() == room && sessions.containsKey(entry.getKey()))
        .map(
            entry ->
                new Delivery(
                    sessions.get(entry.getKey()),
                    new ServerMessage.RoomUpdated(room.viewFor(entry.getValue().playerId()))))
        .toList();
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
