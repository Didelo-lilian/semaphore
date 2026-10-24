package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Player;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import java.io.IOException;
import java.util.Map;
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

@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler {

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

  /** Where a connection is: the room it joined and the player it speaks for. */
  private record Seat(Room room, String playerId) {}

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
  protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
    var session = sessions.get(raw.getId());
    try {
      var incoming = json.readValue(message.getPayload(), ClientMessage.class);
      switch (incoming) {
        case ClientMessage.CreateRoom create -> enter(session, rooms.create(), create.name());
        case ClientMessage.JoinRoom join ->
            enter(
                session,
                rooms.find(join.code()).orElseThrow(() -> new IllegalMove("no such room")),
                join.name());
        case ClientMessage.Rejoin rejoin -> rejoin(session, rejoin);
        case ClientMessage.JoinTeam join -> command(session, new Command.JoinTeam(join.team()));
        case ClientMessage.Start start -> command(session, new Command.Start());
        case ClientMessage.Signal signal ->
            command(session, new Command.Signal(signal.word(), signal.number()));
        case ClientMessage.Reveal reveal -> command(session, new Command.Reveal(reveal.index()));
        case ClientMessage.Pass pass -> command(session, new Command.Pass());
      }
    } catch (JacksonException e) {
      send(session, new ServerMessage.Error("unreadable message"));
    } catch (IllegalMove e) {
      send(session, new ServerMessage.Error(e.getMessage()));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) throws IOException {
    sessions.remove(raw.getId());
    var seat = seats.remove(raw.getId());
    if (seat != null) {
      synchronized (seat.room()) {
        seat.room().setConnected(seat.playerId(), false);
        broadcast(seat.room());
      }
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    session.close(CloseStatus.SERVER_ERROR);
  }

  private void enter(WebSocketSession session, Room room, String name) throws IOException {
    synchronized (room) {
      var player = room.join(name);
      seats.put(session.getId(), new Seat(room, player.getId()));
      send(
          session,
          new ServerMessage.Joined(
              player.getId(), player.getToken(), room.viewFor(player.getId())));
      broadcast(room);
    }
  }

  private void rejoin(WebSocketSession session, ClientMessage.Rejoin rejoin) throws IOException {
    var room = rooms.find(rejoin.code()).orElseThrow(() -> new IllegalMove("no such room"));
    synchronized (room) {
      Player player =
          room.byToken(rejoin.token()).orElseThrow(() -> new IllegalMove("unknown player"));
      seats.put(session.getId(), new Seat(room, player.getId()));
      room.setConnected(player.getId(), true);
      send(
          session,
          new ServerMessage.Joined(
              player.getId(), player.getToken(), room.viewFor(player.getId())));
      broadcast(room);
    }
  }

  private void command(WebSocketSession session, Command command) throws IOException {
    var seat = seats.get(session.getId());
    if (seat == null) {
      throw new IllegalMove("join a room first");
    }
    synchronized (seat.room()) {
      seat.room().apply(seat.playerId(), command);
      broadcast(seat.room());
    }
  }

  /**
   * Every connected player of the room gets their own view: only the Signaleurs see the hidden
   * cards.
   */
  private void broadcast(Room room) {
    for (var entry : seats.entrySet()) {
      var session = sessions.get(entry.getKey());
      if (entry.getValue().room() == room && session != null) {
        try {
          send(session, new ServerMessage.RoomUpdated(room.viewFor(entry.getValue().playerId())));
        } catch (IOException e) {
          // a connection that breaks is closed by the container, it must not stop the others
        }
      }
    }
  }

  private void send(WebSocketSession session, ServerMessage message) throws IOException {
    session.sendMessage(new TextMessage(json.writeValueAsString(message)));
  }
}
