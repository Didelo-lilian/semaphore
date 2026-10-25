package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Cluster;
import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Player;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomEvent;
import fr.didelo.semaphore.game.RoomEvents;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.RoomView;
import fr.didelo.semaphore.game.Unavailable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
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
 * One connection per player or spectator, held by this replica only. A change loads the room,
 * applies the command and saves it ({@link RoomRegistry}); the views of the connections held here
 * are built from the room that was saved and sent afterwards, so a slow connection never holds a
 * room, and the other replicas are told through {@link RoomEvents} to do the same for theirs. Each
 * view carries the room's version, so a client drops one that arrives late. The answer to a message
 * carries the message's id, and what goes to the other players carries none.
 */
@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler implements SmartLifecycle {

  private static final int SEND_TIME_LIMIT_MS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;
  // A friend mistypes a code once or twice; five unknown codes on one connection is a scan.
  private static final int MAX_MISSES = 5;
  private static final CloseStatus REPLACED =
      CloseStatus.NORMAL.withReason("replaced by a newer connection");
  private static final CloseStatus GONE = CloseStatus.NORMAL.withReason("unknown player");

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
  private final RoomEvents events;
  private final Cluster cluster;

  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, Seat> seats = new ConcurrentHashMap<>();
  private final Map<String, Integer> misses = new ConcurrentHashMap<>();
  // Closed connections whose room could not be told while the store was out of reach.
  private final Queue<Seat> unsettled = new ConcurrentLinkedQueue<>();
  // The version of each room that the views built here come from, to skip what is already sent.
  private final Map<String, Integer> sent = new ConcurrentHashMap<>();
  private volatile boolean running;

  @Override
  public void start() {
    events.subscribe(this::onEvent, this::checkVersions);
    running = true;
  }

  /**
   * A replica that stops on purpose closes its connections without writing anyone absent: the
   * clients take their seats back on another replica, and whoever does not is marked absent when
   * this replica's heartbeat has run out.
   */
  @Override
  public void stop() {
    running = false;
    seats.clear();
    sessions.values().forEach(session -> close(session, CloseStatus.GOING_AWAY));
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  // Stops before the web server does, so the connections are closed while the replica is alive.
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

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
      release(seat);
    }
  }

  /** A connection is gone: the room stops counting it. The next check tries again if it fails. */
  private void release(Seat seat) {
    try {
      var released =
          rooms.change(
              seat.code(),
              room -> {
                if (seat.spectator()) {
                  room.unwatch(cluster.id());
                } else {
                  room.disconnect(seat.playerId(), cluster.id());
                }
              });
      if (released.changed()) {
        deliver(changed(released.room(), null, null));
      }
    } catch (IllegalMove e) {
      // The room was dropped while spectators watched.
    } catch (Unavailable e) {
      unsettled.add(seat);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    session.close(CloseStatus.SERVER_ERROR);
  }

  /** Sends the views of a room that a sweep has just changed. */
  public void roomChanged(Room room) {
    deliver(changed(room, null, null));
  }

  /**
   * Publication can lose a message, and a replica may have been cut off for a while: every few
   * seconds, and when the subscription comes back, the closes that could not be written are tried
   * again and the rooms held here are read again, set right and their views sent if they are
   * behind.
   */
  @Scheduled(fixedDelay = 3_000)
  void checkVersions() {
    for (var pending = unsettled.size(); pending > 0; pending--) {
      var seat = unsettled.poll();
      // A player who has taken their seat back here since is connected: nothing to undo.
      if (seat != null && (seat.spectator() || !seats.containsValue(seat))) {
        release(seat);
      }
    }
    var codes = seats.values().stream().map(Seat::code).collect(Collectors.toSet());
    sent.keySet().retainAll(codes);
    for (var code : codes) {
      try {
        refresh(code);
      } catch (Unavailable e) {
        // This room cannot be read now: the next check tries again, and the others go on.
      }
    }
  }

  private void onEvent(RoomEvent event) {
    if (event.replica().equals(cluster.id())) {
      return;
    }
    try {
      switch (event) {
        case RoomEvent.Changed changed -> {
          if (changed.version() > sent.getOrDefault(changed.code(), -1)) {
            refresh(changed.code());
          }
        }
        // The room says who holds the player: the replica that does not closes its connection.
        case RoomEvent.SeatTaken taken -> refresh(taken.code());
      }
    } catch (Unavailable e) {
      // The next check of versions repairs what this event would have.
    }
  }

  /** Sends the views of a room, if some connection is held here and the room is ahead of them. */
  private void refresh(String code) {
    // Taken before the room is read: a seat made since is not judged on a room older than itself.
    var held = new HashMap<String, Seat>();
    seats.forEach(
        (sessionId, seat) -> {
          if (seat.code().equals(code)) {
            held.put(sessionId, seat);
          }
        });
    if (!held.isEmpty()) {
      rooms
          .find(code)
          .map(room -> reconcile(room, held))
          .filter(room -> room.version() > sent.getOrDefault(code, -1))
          .ifPresent(room -> deliver(views(room, null, null)));
    }
  }

  /**
   * The room and the connections held here must agree on who is connected here. They do not after a
   * replica was judged dead while its connections were fine (its players are marked absent), or
   * when a {@code seatTaken} was lost (the player is held elsewhere and the old connection is still
   * open). The room is the one that is set right, and the connections that have lost their seat are
   * closed. Returns the room as it is saved.
   */
  private Room reconcile(Room room, Map<String, Seat> held) {
    var players =
        room.state().players().stream()
            .collect(Collectors.toMap(RoomState.PlayerState::id, Function.identity()));
    var back = new ArrayList<String>();
    for (var entry : held.entrySet()) {
      var seat = entry.getValue();
      if (seat.spectator()) {
        continue;
      }
      var player = players.get(seat.playerId());
      if (player == null || (player.replica() != null && !player.replica().equals(cluster.id()))) {
        if (seats.remove(entry.getKey(), seat)) {
          var session = sessions.get(entry.getKey());
          if (session != null) {
            close(session, player == null ? GONE : REPLACED);
          }
        }
      } else if (player.replica() == null && !player.left()) {
        back.add(seat.playerId());
      }
    }
    if (back.isEmpty()) {
      return room;
    }
    try {
      var saved = rooms.change(room.code(), r -> back.forEach(id -> r.reclaim(id, cluster.id())));
      // A connection that closed meanwhile found nobody to mark absent: the room is told now.
      for (var id : back) {
        var seat = new Seat(room.code(), id);
        if (!seats.containsValue(seat)) {
          release(seat);
        }
      }
      if (saved.changed()) {
        deliver(changed(saved.room(), null, null));
      }
      return saved.room();
    } catch (IllegalMove e) {
      // The room was dropped meanwhile: the next check closes its connections.
      return room;
    }
  }

  private List<Delivery> create(WebSocketSession session, String id, String name) {
    requireToEnter(session);
    var created = rooms.create(name, cluster.id());
    return welcome(session, id, created.room(), created.result());
  }

  private List<Delivery> enter(WebSocketSession session, String id, Room found, String name) {
    requireToEnter(session);
    var joined = rooms.update(found.code(), room -> room.join(name, cluster.id()));
    return welcome(session, id, joined.room(), joined.result());
  }

  private List<Delivery> watch(WebSocketSession session, String id, Room found) {
    requireToEnter(session);
    var watched = rooms.change(found.code(), room -> room.watch(cluster.id()));
    seats.put(session.getId(), new Seat(found.code(), null));
    return changed(watched.room(), session.getId(), id);
  }

  private List<Delivery> rejoin(WebSocketSession session, ClientMessage.Rejoin rejoin) {
    var replaced = List.<WebSocketSession>of();
    Seat old = null;
    try {
      var found = lookUp(session, rejoin.code());
      requireToEnter(session);
      var known =
          found.byToken(rejoin.token()).orElseThrow(() -> new IllegalMove("unknown player"));
      // Out of the way before the change is saved, so the old connection closing cannot mark
      // absent a player who is back.
      old = new Seat(found.code(), known.getId());
      replaced = takeSeats(old);
      var rejoined =
          rooms.update(
              found.code(),
              room -> {
                var player =
                    room.byToken(rejoin.token())
                        .orElseThrow(() -> new IllegalMove("unknown player"));
                room.reconnect(player.getId(), cluster.id());
                return player;
              });
      var deliveries = welcome(session, rejoin.id(), rejoined.room(), rejoined.result());
      events.publish(new RoomEvent.SeatTaken(found.code(), known.getId(), cluster.id()));
      replaced.forEach(other -> close(other, REPLACED));
      return deliveries;
    } catch (IllegalMove e) {
      // The player or the room is gone: so are the connections that held their seat.
      replaced.forEach(other -> close(other, GONE));
      throw e;
    } catch (Unavailable e) {
      // The client takes a closed connection for a lost one and tries its seat again, where an
      // answer would make it forget the seat. The old connections have lost their seat for good,
      // and their close finds none to release: the next check does it if the player is not back.
      var busy = CloseStatus.SERVICE_OVERLOAD.withReason(e.getMessage());
      close(session, busy);
      replaced.forEach(other -> close(other, busy));
      if (!replaced.isEmpty()) {
        unsettled.add(old);
      }
      return List.of();
    }
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
                room.unwatch(cluster.id());
              } else {
                room.leave(seat.playerId());
              }
            });
    seats.remove(session.getId(), seat);
    return changed(left.room(), null, null);
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
    return changed(done.room(), session.getId(), id);
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
  private List<WebSocketSession> takeSeats(Seat seat) {
    var taken = new ArrayList<WebSocketSession>();
    seats
        .entrySet()
        .removeIf(
            entry -> {
              var same = entry.getValue().equals(seat);
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
    deliveries.addAll(changed(room, null, null));
    return deliveries;
  }

  /** The room has been saved: the other replicas are told, and the views held here are built. */
  private List<Delivery> changed(Room room, String senderSessionId, String id) {
    events.publish(new RoomEvent.Changed(room.code(), room.version(), cluster.id()));
    return views(room, senderSessionId, id);
  }

  /**
   * Every connected player of the room gets their own view: only the Signaleurs see the hidden
   * cards. The spectators all get the same one, built once. The view of the connection that sent
   * the message carries its id, the others none.
   */
  private List<Delivery> views(Room room, String senderSessionId, String id) {
    sent.merge(room.code(), room.version(), Math::max);
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
