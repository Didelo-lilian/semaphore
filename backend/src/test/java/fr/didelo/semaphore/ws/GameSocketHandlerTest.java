package fr.didelo.semaphore.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.didelo.semaphore.game.MutableClock;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.WordList;
import fr.didelo.semaphore.store.InMemoryRoomEvents;
import fr.didelo.semaphore.store.InMemoryRoomStore;
import fr.didelo.semaphore.store.LocalCluster;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.databind.json.JsonMapper;

class GameSocketHandlerTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final MutableClock clock = new MutableClock();
  private final RoomRegistry registry =
      new RoomRegistry(new InMemoryRoomStore(), clock, new WordList(), 2);
  private final LocalCluster cluster = new LocalCluster();
  private final GameSocketHandler handler =
      new GameSocketHandler(json, registry, new InMemoryRoomEvents(), cluster);
  private final RoomSweeper sweeper = new RoomSweeper(registry, cluster, handler);

  private WebSocketSession connect(String id) throws Exception {
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    handler.afterConnectionEstablished(session);
    return session;
  }

  private void receive(WebSocketSession session, String message) {
    handler.handleTextMessage(session, new TextMessage(message));
  }

  private String lastPayload(WebSocketSession session) throws Exception {
    var sent = ArgumentCaptor.forClass(TextMessage.class);
    verify(session, atLeastOnce()).sendMessage(sent.capture());
    return sent.getValue().getPayload();
  }

  /**
   * Four players, two per team, game started: the sessions of p0 to p3, the host first, the first
   * two on Bâbord.
   */
  private List<WebSocketSession> startedGame() throws Exception {
    var sessions = new ArrayList<WebSocketSession>();
    var host = connect("p0");
    receive(host, "{\"type\":\"createRoom\",\"name\":\"p0\"}");
    var code = json.readTree(lastPayload(host)).path("room").path("code").asString();
    sessions.add(host);
    for (var i = 1; i < 4; i++) {
      var session = connect("p" + i);
      receive(session, "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"p" + i + "\"}");
      sessions.add(session);
    }
    for (var i = 0; i < 4; i++) {
      receive(
          sessions.get(i),
          "{\"type\":\"joinTeam\",\"team\":\"" + (i < 2 ? "BABORD" : "TRIBORD") + "\"}");
    }
    receive(host, "{\"type\":\"start\"}");
    return sessions;
  }

  @Test
  void aConnectionThatBreaksDoesNotStopTheOthersFromGettingTheUpdate() throws Exception {
    var host = connect("host");
    var broken = connect("broken");
    receive(host, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    var code = json.readTree(lastPayload(host)).path("room").path("code").asString();
    doThrow(new SessionLimitExceededException("buffer full", CloseStatus.SESSION_NOT_RELIABLE))
        .when(broken)
        .sendMessage(any());
    receive(broken, "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");

    receive(host, "{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");

    var update = json.readTree(lastPayload(host));
    assertThat(update.path("type").asString()).isEqualTo("roomUpdated");
    assertThat(update.path("room").path("players").path(0).path("team").asString())
        .isEqualTo("BABORD");
  }

  @Test
  void aRoomThatCouldNotBeEnteredIsNotKept() throws Exception {
    var session = connect("s1");

    receive(session, "{\"type\":\"createRoom\",\"name\":\"   \"}");

    assertThat(lastPayload(session))
        .contains("\"error\"")
        .contains("a name has 1 to 20 characters");
    assertThat(registry.codes()).isEmpty();
  }

  @Test
  void aSecondRoomOnTheSameConnectionIsRefusedAndNotKept() throws Exception {
    var session = connect("s1");
    receive(session, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");

    receive(session, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");

    assertThat(lastPayload(session)).contains("you are already in a room");
    assertThat(registry.codes()).hasSize(1);
  }

  @Test
  void theSweepTellsTheLobbyThatAnAbsentPlayerIsGone() throws Exception {
    var host = connect("host");
    var bob = connect("bob");
    receive(host, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    var code = json.readTree(lastPayload(host)).path("room").path("code").asString();
    receive(bob, "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");
    handler.afterConnectionClosed(bob, CloseStatus.GOING_AWAY);

    clock.advance(Room.LOBBY_GRACE);
    sweeper.dropAbsent();
    assertThat(json.readTree(lastPayload(host)).path("room").path("players")).hasSize(2);

    clock.advance(Duration.ofSeconds(1));
    sweeper.dropAbsent();
    assertThat(json.readTree(lastPayload(host)).path("room").path("players")).hasSize(1);
  }

  @Test
  void theCountOfUnknownRoomsGoesWithTheConnection() throws Exception {
    var unknown = "{\"type\":\"watch\",\"code\":\"00000\"}";
    var first = connect("s1");
    for (var i = 0; i < 4; i++) {
      receive(first, unknown);
    }
    handler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);

    var second = connect("s1");
    for (var i = 0; i < 4; i++) {
      receive(second, unknown);
    }
    verify(second, never()).close(any());

    receive(second, unknown);
    verify(second).close(CloseStatus.POLICY_VIOLATION.withReason("too many unknown rooms"));
  }

  @Test
  void aMessageThatArrivesAfterTheConnectionClosedIsIgnored() throws Exception {
    var session = connect("s1");
    handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

    receive(session, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    receive(session, "{\"type\":\"watch\",\"code\":\"00000\"}");

    assertThat(registry.codes()).isEmpty();
    verify(session, never()).sendMessage(any());
  }

  @Test
  void aLeaveFromAConnectionWithoutASeatIsRefused() throws Exception {
    var session = connect("s1");

    receive(session, "{\"type\":\"leave\"}");

    assertThat(lastPayload(session)).contains("\"error\"").contains("join a room first");
  }

  @Test
  void theSweepPassesTheTurnWhenTheSignaleurInPlayStaysAway() throws Exception {
    var sessions = startedGame();
    var lookout = sessions.get(1);
    var turn = json.readTree(lastPayload(lookout)).path("room").path("turn").asString();
    var signaler = sessions.get(turn.equals("BABORD") ? 0 : 2);

    handler.afterConnectionClosed(signaler, CloseStatus.GOING_AWAY);
    assertThat(json.readTree(lastPayload(lookout)).path("room").path("turnPassesIn").asInt())
        .isEqualTo(60);

    clock.advance(Room.TURN_GRACE.minusSeconds(1));
    sweeper.expire();
    assertThat(json.readTree(lastPayload(lookout)).path("room").path("turn").asString())
        .isEqualTo(turn);

    clock.advance(Duration.ofSeconds(1));
    sweeper.expire();
    var room = json.readTree(lastPayload(lookout)).path("room");
    assertThat(room.path("turn").asString()).isNotEqualTo(turn);
    assertThat(room.path("turnPassesIn").isNull()).isTrue();
  }
}
