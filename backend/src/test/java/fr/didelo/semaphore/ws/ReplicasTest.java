package fr.didelo.semaphore.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.didelo.semaphore.game.MutableClock;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomEvent;
import fr.didelo.semaphore.game.RoomEvents;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.Unavailable;
import fr.didelo.semaphore.game.WordList;
import fr.didelo.semaphore.store.InMemoryRoomEvents;
import fr.didelo.semaphore.store.InMemoryRoomStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Two replicas of the handler over one store, as two backends would be over Redis. */
class ReplicasTest {

  /** The channel between the replicas, which can lose what is published. */
  private static final class Channel implements RoomEvents {
    final InMemoryRoomEvents inner = new InMemoryRoomEvents();
    final List<RoomEvent> published = new ArrayList<>();
    boolean losing;

    @Override
    public void publish(RoomEvent event) {
      published.add(event);
      if (!losing) {
        inner.publish(event);
      }
    }

    @Override
    public void subscribe(Consumer<RoomEvent> listener, Runnable subscribed) {
      inner.subscribe(listener, subscribed);
    }
  }

  private final JsonMapper json = JsonMapper.builder().build();
  private final MutableClock clock = new MutableClock();
  private final InMemoryRoomStore store = spy(new InMemoryRoomStore());
  private final RoomRegistry registry = new RoomRegistry(store, clock, new WordList(), 2);
  private final Channel channel = new Channel();
  private final FakeCluster.Shared shared = new FakeCluster.Shared();
  private final FakeCluster cluster1 = new FakeCluster("replica-1", shared);
  private final FakeCluster cluster2 = new FakeCluster("replica-2", shared);
  private final GameSocketHandler first = new GameSocketHandler(json, registry, channel, cluster1);
  private final GameSocketHandler second = new GameSocketHandler(json, registry, channel, cluster2);
  private final RoomSweeper sweeper1 = new RoomSweeper(registry, cluster1, first);
  private final RoomSweeper sweeper2 = new RoomSweeper(registry, cluster2, second);

  ReplicasTest() {
    first.start();
    second.start();
  }

  private WebSocketSession connect(GameSocketHandler replica, String id) {
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    replica.afterConnectionEstablished(session);
    return session;
  }

  private void receive(GameSocketHandler replica, WebSocketSession session, String message) {
    replica.handleTextMessage(session, new TextMessage(message));
  }

  private List<JsonNode> received(WebSocketSession session) {
    return mockingDetails(session).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
        .map(invocation -> json.readTree(((TextMessage) invocation.getArgument(0)).getPayload()))
        .toList();
  }

  private JsonNode last(WebSocketSession session) {
    return received(session).getLast();
  }

  /** The token the first answer to a player gave them. */
  private String token(WebSocketSession session) {
    return received(session).getFirst().path("token").asString();
  }

  private String createRoom(GameSocketHandler replica, WebSocketSession host) {
    receive(replica, host, "{\"type\":\"createRoom\",\"name\":\"p0\"}");
    return last(host).path("room").path("code").asString();
  }

  private void join(GameSocketHandler replica, WebSocketSession session, String code, String name) {
    receive(
        replica,
        session,
        "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"" + name + "\"}");
  }

  /**
   * Four players, the host first, two per team: even ones on the first replica, odd on the second.
   */
  private List<WebSocketSession> startedGame() {
    var sessions = new ArrayList<WebSocketSession>();
    sessions.add(connect(first, "p0"));
    var code = createRoom(first, sessions.get(0));
    for (var i = 1; i < 4; i++) {
      sessions.add(connect(i % 2 == 0 ? first : second, "p" + i));
      join(i % 2 == 0 ? first : second, sessions.get(i), code, "p" + i);
    }
    for (var i = 0; i < 4; i++) {
      receive(
          i % 2 == 0 ? first : second,
          sessions.get(i),
          "{\"type\":\"joinTeam\",\"team\":\"" + (i < 2 ? "BABORD" : "TRIBORD") + "\"}");
    }
    receive(first, sessions.get(0), "{\"type\":\"start\"}");
    return sessions;
  }

  @Test
  void aPlayerOnEachReplicaSeesWhatTheOtherDoes() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(second, "guest");

    join(second, guest, code, "p1");

    assertThat(last(host).path("room").path("players")).hasSize(2);
    assertThat(last(guest).path("room").path("players")).hasSize(2);
    assertThat(last(host).path("id").isMissingNode()).isTrue();
  }

  @Test
  void theAnswerToTheSenderStillCarriesItsIdOnTheOtherReplicaToo() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");

    receive(second, guest, "{\"type\":\"joinTeam\",\"id\":\"9\",\"team\":\"BABORD\"}");

    assertThat(last(guest).path("id").asString()).isEqualTo("9");
    assertThat(last(host).path("id").isMissingNode()).isTrue();
    assertThat(last(host).path("room").path("players").path(1).path("team").asString())
        .isEqualTo("BABORD");
  }

  @Test
  void theHiddenCardsNeverGoOverTheChannelNorToWhoIsNotASignaleur() {
    var sessions = startedGame();
    var watcher = connect(second, "watcher");
    receive(
        second,
        watcher,
        "{\"type\":\"watch\",\"code\":\""
            + last(sessions.get(0)).path("room").path("code").asString()
            + "\"}");

    var turn = last(sessions.get(1)).path("room").path("turn").asString();
    var signaler = sessions.get(turn.equals("BABORD") ? 0 : 2);
    receive(first, signaler, "{\"type\":\"signal\",\"word\":\"voyage\",\"number\":1}");

    for (var event : channel.published) {
      assertThat(json.writeValueAsString(event)).doesNotContain("board", "kind", "token");
    }
    assertThat(received(watcher))
        .allSatisfy(m -> assertThat(m.toString()).doesNotContain("\"kind\":\""));
    assertThat(received(sessions.get(1)).getLast().toString()).doesNotContain("\"kind\":\"");
    assertThat(last(sessions.get(turn.equals("BABORD") ? 0 : 2)).toString())
        .contains("\"kind\":\"");
    assertThat(last(watcher).path("room").path("phase").asString()).isEqualTo("GUESS");
  }

  @Test
  void aRejoinOnTheOtherReplicaTakesTheSeatAndClosesTheOldConnection() throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var other = connect(second, "other");
    join(second, other, code, "p1");
    var again = connect(second, "again");

    receive(
        second,
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");

    verify(host).close(CloseStatus.NORMAL.withReason("replaced by a newer connection"));
    assertThat(last(again).path("room").path("you").asString())
        .isEqualTo(last(host).path("room").path("you").asString());
    // The old connection closes for real afterwards: the player stays connected.
    first.afterConnectionClosed(host, CloseStatus.NORMAL);
    assertThat(last(other).path("room").path("players").path(0).path("connected").asBoolean())
        .isTrue();
    assertThat(
            registry
                .find(code)
                .orElseThrow()
                .viewFor(last(again).path("room").path("you").asString())
                .players())
        .allMatch(p -> p.connected());
  }

  @Test
  void aSeatTakenMessageThatIsLostIsRepairedByTheCheckAndTheOldConnectionClosingIsHarmless()
      throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var other = connect(second, "other");
    join(second, other, code, "p1");
    channel.losing = true;
    var again = connect(second, "again");
    receive(
        second,
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");
    verify(host, never()).close(any());

    first.checkVersions();
    verify(host).close(CloseStatus.NORMAL.withReason("replaced by a newer connection"));
    first.afterConnectionClosed(host, CloseStatus.GOING_AWAY);

    var room = registry.find(code).orElseThrow();
    assertThat(room.viewFor(last(again).path("room").path("you").asString()).players())
        .allMatch(p -> p.connected());
  }

  @Test
  void aLostMessageIsRepairedByTheCheckOfVersions() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    channel.losing = true;

    receive(first, host, "{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");
    assertThat(last(guest).path("room").path("players").path(0).path("team").isNull()).isTrue();
    channel.losing = false;
    second.checkVersions();

    assertThat(last(guest).path("room").path("players").path(0).path("team").asString())
        .isEqualTo("BABORD");
    var sent = received(guest).size();
    second.checkVersions();
    assertThat(received(guest)).hasSize(sent);
  }

  @Test
  void aReplicaThatStopsClosesItsConnectionsWithoutLosingTheRoomOrMarkingAnyoneAbsent()
      throws Exception {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();

    first.stop();

    verify(sessions.get(0)).close(CloseStatus.GOING_AWAY);
    verify(sessions.get(2)).close(CloseStatus.GOING_AWAY);
    verify(sessions.get(1), never()).close(any());
    first.afterConnectionClosed(sessions.get(0), CloseStatus.GOING_AWAY);
    first.afterConnectionClosed(sessions.get(2), CloseStatus.GOING_AWAY);
    var room = registry.find(code).orElseThrow();
    assertThat(room.viewFor(last(sessions.get(1)).path("room").path("you").asString()).players())
        .allMatch(p -> p.connected());
  }

  @Test
  void whoDoesNotComeBackIsMarkedAbsentOnceTheHeartbeatOfTheStoppedReplicaRunsOut() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    var token = token(sessions.get(0));
    first.stop();
    var back = connect(second, "back");
    receive(
        second,
        back,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");
    shared.alive.remove("replica-1");

    sweeper2.deadReplicas();

    var view = last(sessions.get(1)).path("room").path("players");
    assertThat(view.get(0).path("connected").asBoolean()).isTrue();
    assertThat(view.get(1).path("connected").asBoolean()).isTrue();
    assertThat(view.get(2).path("connected").asBoolean()).isFalse();
    assertThat(view.get(3).path("connected").asBoolean()).isTrue();
  }

  @Test
  void aReplicaThatHasNoHeartbeatItselfJudgesNobody() {
    var sessions = startedGame();
    shared.alive.clear();

    sweeper2.deadReplicas();

    assertThat(last(sessions.get(1)).path("room").path("players"))
        .allSatisfy(p -> assertThat(p.path("connected").asBoolean()).isTrue());
  }

  @Test
  void aSweepRunsOnOneReplicaPerTickAndTwiceDoesNoHarm() {
    var sessions = startedGame();
    var turn = last(sessions.get(1)).path("room").path("turn").asString();
    var signaler = sessions.get(turn.equals("BABORD") ? 0 : 2);
    first.afterConnectionClosed(signaler, CloseStatus.GOING_AWAY);
    clock.advance(Room.TURN_GRACE);
    shared.tick();

    sweeper1.expire();
    var version = last(sessions.get(1)).path("room").path("version").asInt();
    sweeper2.expire();
    verify(store, times(1)).codes();

    assertThat(last(sessions.get(1)).path("room").path("turn").asString()).isNotEqualTo(turn);
    assertThat(last(sessions.get(1)).path("room").path("version").asInt()).isEqualTo(version);
    shared.tick();
    sweeper2.expire();
    verify(store, times(2)).codes();
    assertThat(last(sessions.get(1)).path("room").path("version").asInt()).isEqualTo(version);
  }

  @Test
  void whoWatchesOnAReplicaThatDiesIsNotCountedAnyMore() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    var watcher = connect(first, "watcher");
    receive(first, watcher, "{\"type\":\"watch\",\"code\":\"" + code + "\"}");
    assertThat(last(sessions.get(1)).path("room").path("spectators").asInt()).isEqualTo(1);
    shared.alive.remove("replica-1");

    sweeper2.deadReplicas();

    assertThat(last(sessions.get(1)).path("room").path("spectators").asInt()).isZero();
  }

  @Test
  void aReplicaJudgedDeadWhileItsConnectionsAreFineIsSetRightByTheCheck() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    channel.losing = true;
    shared.alive.remove("replica-1");
    sweeper2.deadReplicas();
    assertThat(registry.find(code).orElseThrow().state().players())
        .filteredOn(p -> p.replica() == null)
        .hasSize(2);
    shared.alive.add("replica-1");

    first.checkVersions();

    assertThat(registry.find(code).orElseThrow().state().players())
        .extracting(RoomState.PlayerState::replica)
        .containsExactly("replica-1", "replica-2", "replica-1", "replica-2");
  }

  @Test
  void aReplicaJudgedDeadIsSetRightAtOnceWhenItHearsOfIt() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    shared.alive.remove("replica-1");

    sweeper2.deadReplicas();

    assertThat(registry.find(code).orElseThrow().state().players())
        .extracting(RoomState.PlayerState::replica)
        .containsExactly("replica-1", "replica-2", "replica-1", "replica-2");
  }

  @Test
  void aCloseThatCouldNotBeWrittenIsTriedAgainByTheNextCheck() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    doThrow(new Unavailable("the server is busy, try again"))
        .doCallRealMethod()
        .when(store)
        .replace(any(), anyInt());

    first.afterConnectionClosed(host, CloseStatus.NORMAL);
    assertThat(last(guest).path("room").path("players").path(0).path("connected").asBoolean())
        .isTrue();
    first.checkVersions();

    assertThat(last(guest).path("room").path("players").path(0).path("connected").asBoolean())
        .isFalse();
  }

  @Test
  void aPlayerWhoWasSweptAwayWhileConnectedLosesTheConnectionInsteadOfBreakingTheRoom()
      throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    registry.change(code, room -> room.leave(room.state().players().get(1).id()));

    first.checkVersions();
    second.checkVersions();

    verify(guest).close(CloseStatus.NORMAL.withReason("unknown player"));
    verify(host, never()).close(any());
  }

  @Test
  void twoRejoinsOfOnePlayerOnTwoReplicasLeaveOnlyTheOneTheRoomHolds() throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var rejoin = "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}";
    channel.losing = true;
    var onFirst = connect(first, "on-first");
    receive(first, onFirst, rejoin);
    var onSecond = connect(second, "on-second");
    receive(second, onSecond, rejoin);
    var player = last(onSecond).path("room").path("you").asString();

    channel.inner.publish(new RoomEvent.SeatTaken(code, player, "replica-1"));
    channel.inner.publish(new RoomEvent.SeatTaken(code, player, "replica-2"));

    verify(onFirst).close(CloseStatus.NORMAL.withReason("replaced by a newer connection"));
    verify(onSecond, never()).close(any());
  }

  @Test
  void aSeatMadeWhileTheRoomIsBeingReadIsNotJudgedOnTheRoomAsItWas() throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var guest = connect(first, "guest");
    var reading = new AtomicBoolean(true);
    doAnswer(
            call -> {
              var found = (Optional<?>) call.callRealMethod();
              if (reading.getAndSet(false)) {
                join(first, guest, code, "p1");
              }
              return found;
            })
        .when(store)
        .find(code);

    first.checkVersions();

    verify(guest, never()).close(any());
    assertThat(registry.find(code).orElseThrow().state().players())
        .extracting(RoomState.PlayerState::replica)
        .containsExactly("replica-1", "replica-1");
  }

  @Test
  void aConnectionThatClosesWhileItsPlayerIsBeingMarkedBackLeavesThemAbsent() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    channel.losing = true;
    shared.alive.remove("replica-1");
    sweeper2.deadReplicas();
    shared.alive.add("replica-1");
    var closing = new AtomicBoolean(true);
    doAnswer(
            call -> {
              if (closing.getAndSet(false)) {
                first.afterConnectionClosed(sessions.get(0), CloseStatus.NORMAL);
              }
              return call.callRealMethod();
            })
        .when(store)
        .replace(any(), anyInt());

    first.checkVersions();

    assertThat(registry.find(code).orElseThrow().state().players())
        .extracting(RoomState.PlayerState::replica)
        .containsExactly(null, "replica-2", "replica-1", "replica-2");
  }

  @Test
  void aCloseQueuedForARetryIsDroppedOnceThePlayerHasTakenTheirSeatBackHere() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    doThrow(new Unavailable("the server is busy, try again"))
        .doCallRealMethod()
        .when(store)
        .replace(any(), anyInt());
    first.afterConnectionClosed(host, CloseStatus.NORMAL);
    var again = connect(first, "again");
    receive(
        first,
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");
    var version = registry.find(code).orElseThrow().version();

    first.checkVersions();

    assertThat(registry.find(code).orElseThrow().version()).isEqualTo(version);
    assertThat(last(guest).path("room").path("players").path(0).path("connected").asBoolean())
        .isTrue();
  }

  @Test
  void aRejoinThatCannotBeSavedLeavesTheOldSeatToBeReleasedByTheNextCheck() throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    var again = connect(first, "again");
    doThrow(new Unavailable("the server is busy, try again"))
        .doCallRealMethod()
        .when(store)
        .replace(any(), anyInt());

    receive(
        first,
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");
    first.afterConnectionClosed(host, CloseStatus.SERVICE_OVERLOAD);
    first.checkVersions();

    assertThat(last(guest).path("room").path("players").path(0).path("connected").asBoolean())
        .isFalse();
  }

  @Test
  void aRejoinWhoseRoomIsGoneClosesTheOldConnectionToo() throws Exception {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    var token = token(host);
    var again = connect(first, "again");
    doCallRealMethod().doReturn(Optional.empty()).doCallRealMethod().when(store).find(code);

    receive(
        first,
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}");

    verify(host).close(CloseStatus.NORMAL.withReason("unknown player"));
    assertThat(last(again).path("reason").asString()).isEqualTo("no such room");
  }

  @ParameterizedTest
  @MethodSource("aRoomThatFails")
  void aRoomThatCannotBeReadDoesNotStopTheSweepOfTheOthers(RuntimeException failure) {
    var host = connect(first, "host");
    var unreadable = createRoom(first, host);
    var other = connect(first, "other");
    var code = createRoom(first, other);
    var guest = connect(second, "guest");
    join(second, guest, code, "p1");
    second.afterConnectionClosed(guest, CloseStatus.NORMAL);
    clock.advance(Room.LOBBY_GRACE.plusSeconds(1));
    doReturn(List.of(unreadable, code)).when(store).codes();
    doThrow(failure).when(store).find(unreadable);
    shared.tick();

    sweeper1.dropAbsent();

    assertThat(last(other).path("room").path("players")).hasSize(1);
  }

  static Stream<RuntimeException> aRoomThatFails() {
    return Stream.of(
        new Unavailable("the server is busy, try again"),
        new NullPointerException("a field this version needs is missing"));
  }

  @Test
  void aRoomThatBreaksTheCheckDoesNotStopTheCheckOfTheOthers() {
    var hostA = connect(first, "host-a");
    var a = createRoom(first, hostA);
    var guestA = connect(second, "guest-a");
    join(second, guestA, a, "p1");
    var hostB = connect(first, "host-b");
    var b = createRoom(first, hostB);
    var guestB = connect(second, "guest-b");
    join(second, guestB, b, "p1");
    channel.losing = true;
    receive(first, hostA, "{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");
    receive(first, hostB, "{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");

    // The rooms come in no set order: whichever breaks first, the other must be set right.
    doThrow(new NullPointerException("poisoned")).when(store).find(a);
    second.checkVersions();
    assertThat(last(guestB).path("room").path("players").path(0).path("team").asString())
        .isEqualTo("BABORD");
    doCallRealMethod().when(store).find(a);
    doThrow(new NullPointerException("poisoned")).when(store).find(b);
    second.checkVersions();
    assertThat(last(guestA).path("room").path("players").path(0).path("team").asString())
        .isEqualTo("BABORD");
  }

  @Test
  void aSpectatorWhoLeavesWhileTheCountIsBeingSetBackIsTakenOffAgain() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    var leaving = connect(first, "w1");
    receive(first, leaving, "{\"type\":\"watch\",\"code\":\"" + code + "\"}");
    var staying = connect(first, "w2");
    receive(first, staying, "{\"type\":\"watch\",\"code\":\"" + code + "\"}");
    channel.losing = true;
    shared.alive.remove("replica-1");
    sweeper2.deadReplicas();
    shared.alive.add("replica-1");
    var closing = new AtomicBoolean(true);
    doAnswer(
            call -> {
              if (closing.getAndSet(false)) {
                first.afterConnectionClosed(leaving, CloseStatus.NORMAL);
              }
              return call.callRealMethod();
            })
        .when(store)
        .replace(any(), anyInt());

    first.checkVersions();

    assertThat(registry.find(code).orElseThrow().state().spectators())
        .containsExactly(Map.entry("replica-1", 1));
  }

  @Test
  void aRoomThatBreaksWhenAnEventArrivesOrAConnectionClosesDoesNotThrowIntoTheContainer() {
    var host = connect(first, "host");
    var code = createRoom(first, host);
    doThrow(new NullPointerException("poisoned")).when(store).find(code);

    assertThatCode(() -> channel.inner.publish(new RoomEvent.Changed(code, 99, "replica-2")))
        .doesNotThrowAnyException();
    assertThatCode(() -> first.afterConnectionClosed(host, CloseStatus.NORMAL))
        .doesNotThrowAnyException();
  }

  @Test
  void theSpectatorsAReplicaHoldsAreCountedAgainOnceItIsSetRight() {
    var sessions = startedGame();
    var code = last(sessions.get(0)).path("room").path("code").asString();
    for (var name : List.of("w1", "w2")) {
      var watcher = connect(first, name);
      receive(first, watcher, "{\"type\":\"watch\",\"code\":\"" + code + "\"}");
    }
    channel.losing = true;
    shared.alive.remove("replica-1");
    sweeper2.deadReplicas();
    assertThat(registry.find(code).orElseThrow().state().spectators()).isEmpty();
    shared.alive.add("replica-1");

    first.checkVersions();

    assertThat(registry.find(code).orElseThrow().state().spectators())
        .containsExactly(Map.entry("replica-1", 2));
  }
}
