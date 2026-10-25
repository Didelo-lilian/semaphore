package fr.didelo.semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.game.Room;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIT {

  private static final String ALLOWED_ORIGIN = "https://play.didelo.fr";

  @LocalServerPort int port;

  // The application's own mapper, so the test reads the messages as the server writes them.
  @Autowired JsonMapper json;

  private final HttpClient http = HttpClient.newHttpClient();
  private final List<Client> clients = new ArrayList<>();

  /** One player's connection: what the server sends is parsed, queued and the latest room kept. */
  private final class Client {
    private final LinkedBlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    // Everything the server sent, whatever the test waited for.
    private final List<JsonNode> history = new CopyOnWriteArrayList<>();
    private final WebSocket socket;
    private volatile JsonNode joined;
    private volatile JsonNode room;
    private volatile Integer closeCode;
    private volatile String closeReason;

    Client(String origin) {
      socket =
          http.newWebSocketBuilder()
              .header("Origin", origin)
              .buildAsync(
                  URI.create("ws://localhost:" + port + "/ws"),
                  new WebSocket.Listener() {
                    // A message may arrive in several parts: only the last one completes the JSON.
                    private final StringBuilder part = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(
                        WebSocket ws, CharSequence data, boolean last) {
                      part.append(data);
                      if (last) {
                        var message = json.readTree(part.toString());
                        part.setLength(0);
                        var type = message.path("type").asString();
                        if (type.equals("joined")) {
                          joined = message;
                        }
                        if (type.equals("joined") || type.equals("roomUpdated")) {
                          room = message.path("room");
                        }
                        history.add(message);
                        received.add(message);
                      }
                      ws.request(1);
                      return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                      closeCode = code;
                      closeReason = reason;
                      return null;
                    }
                  })
              .join();
      clients.add(this);
    }

    void send(String message) {
      socket.sendText(message, true).join();
    }

    /** The next message of that type that satisfies the condition, others are skipped. */
    JsonNode await(String type, Predicate<JsonNode> where) throws InterruptedException {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var message = received.poll(100, TimeUnit.MILLISECONDS);
        if (message != null
            && message.path("type").asString().equals(type)
            && where.test(message)) {
          return message;
        }
      }
      throw new AssertionError("no " + type + " message arrived in time");
    }

    JsonNode await(String type) throws InterruptedException {
      return await(type, m -> true);
    }

    String error() throws InterruptedException {
      return await("error").path("reason").asString();
    }

    JsonNode player(int index) {
      return room.path("players").path(index);
    }

    void close() {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
  }

  @AfterEach
  void closeAll() {
    clients.forEach(c -> c.socket.abort());
  }

  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
      Thread.sleep(50);
    }
  }

  private String createRoom(Client host, String name) throws InterruptedException {
    host.send("{\"type\":\"createRoom\",\"name\":\"" + name + "\"}");
    return host.await("joined").path("room").path("code").asString();
  }

  private Client playerIn(String code, String name) throws InterruptedException {
    var client = new Client(ALLOWED_ORIGIN);
    client.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"" + name + "\"}");
    client.await("joined");
    return client;
  }

  /** Asks for a room that does not exist, as a join and as a watch in turn. */
  private static void missRooms(Client client, int times) throws InterruptedException {
    for (var i = 0; i < times; i++) {
      client.send(
          i % 2 == 0
              ? "{\"type\":\"joinRoom\",\"code\":\"00000\",\"name\":\"Ana\"}"
              : "{\"type\":\"watch\",\"code\":\"00000\"}");
      assertThat(client.error()).isEqualTo("no such room");
    }
  }

  private static String rejoin(Client player) {
    return "{\"type\":\"rejoin\",\"id\":\"back\",\"code\":\""
        + player.room.path("code").asString()
        + "\",\"token\":\""
        + player.joined.path("token").asString()
        + "\"}";
  }

  private Client spectatorOf(String code) throws InterruptedException {
    var client = new Client(ALLOWED_ORIGIN);
    client.send("{\"type\":\"watch\",\"id\":\"w\",\"code\":\"" + code + "\"}");
    client.await("roomUpdated", m -> m.path("id").asString().equals("w"));
    return client;
  }

  private static boolean watchedBy(JsonNode update, int spectators) {
    return update.path("room").path("spectators").asInt() == spectators;
  }

  private static boolean isTurn(JsonNode update, String phase, String team) {
    return update.path("room").path("phase").asString().equals(phase)
        && update.path("room").path("turn").asString().equals(team);
  }

  /**
   * The team that starts hits both reefs over two of its turns, so the other team wins: the players
   * are the ones of {@link #startedGame()}, the Signaleur of a team first.
   */
  private void playTheReefsOut(List<Client> players) throws Exception {
    var team = players.get(1).room.path("turn").asString();
    var other = team.equals("BABORD") ? "TRIBORD" : "BABORD";
    var first = team.equals("BABORD") ? 0 : 2;
    var second = 2 - first;
    var board = players.get(first).room.path("board");
    var reefs =
        IntStream.range(0, board.size())
            .filter(i -> board.get(i).path("kind").asString().equals("REEF"))
            .toArray();
    var signal = "{\"type\":\"signal\",\"word\":\"voyage\",\"number\":1}";

    players.get(first).send(signal);
    players.get(first + 1).await("roomUpdated", m -> isTurn(m, "GUESS", team));
    players.get(first + 1).send("{\"type\":\"reveal\",\"index\":" + reefs[0] + "}");
    players.get(second + 1).await("roomUpdated", m -> isTurn(m, "SIGNAL", other));
    players.get(second).send(signal);
    players.get(second + 1).await("roomUpdated", m -> isTurn(m, "GUESS", other));
    players.get(second + 1).send("{\"type\":\"pass\"}");
    players.get(first + 1).await("roomUpdated", m -> isTurn(m, "SIGNAL", team));
    players.get(first).send(signal);
    players.get(first + 1).await("roomUpdated", m -> isTurn(m, "GUESS", team));
    players.get(first + 1).send("{\"type\":\"reveal\",\"index\":" + reefs[1] + "}");
  }

  private static boolean sees(JsonNode update, int players) {
    return update.path("room").path("players").size() == players;
  }

  /** Four players, two per team, game started: a1 and a2 on Bâbord, b1 and b2 on Tribord. */
  private List<Client> startedGame() throws InterruptedException {
    var host = new Client(ALLOWED_ORIGIN);
    var code = createRoom(host, "a1");
    var players = List.of(host, playerIn(code, "a2"), playerIn(code, "b1"), playerIn(code, "b2"));
    for (var i = 0; i < players.size(); i++) {
      players
          .get(i)
          .send("{\"type\":\"joinTeam\",\"team\":\"" + (i < 2 ? "BABORD" : "TRIBORD") + "\"}");
    }
    host.await(
        "roomUpdated",
        m ->
            sees(m, 4)
                && m.path("room").path("players").findValues("team").stream()
                    .noneMatch(JsonNode::isNull));
    host.send("{\"type\":\"start\"}");
    for (var player : players) {
      player.await("roomUpdated", m -> m.path("room").path("phase").asString().equals("SIGNAL"));
    }
    return players;
  }

  @Test
  void healthIsUp() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void aPlayerCreatesARoomAndGetsATokenToComeBack() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);

    host.send("{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    var joined = host.await("joined");

    assertThat(joined.path("room").path("code").asString()).matches("[A-HJKMN-Z2-9]{5}");
    assertThat(joined.path("token").asString()).isNotBlank();
    assertThat(joined.path("room").path("phase").asString()).isEqualTo("LOBBY");
    assertThat(joined.path("room").path("players").get(0).path("host").asBoolean()).isTrue();
  }

  @Test
  void everyoneInTheRoomSeesANewcomer() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var code = createRoom(host, "Ana");

    playerIn(code, "Bob");

    var update = host.await("roomUpdated", m -> sees(m, 2));
    assertThat(update.path("room").path("players").path(1).path("name").asString())
        .isEqualTo("Bob");
  }

  @Test
  void aPlayerWhoLeavesTheLobbyOnPurposeIsGoneAtOnce() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var code = createRoom(host, "Ana");
    var bob = playerIn(code, "Bob");
    host.await("roomUpdated", m -> sees(m, 2));

    bob.send("{\"type\":\"leave\"}");
    host.await("roomUpdated", m -> sees(m, 1));
    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(bob));

    assertThat(again.error()).isEqualTo("unknown player");
  }

  @Test
  void aPlayerWhoDropsFromTheLobbyShowsOfflineAndComesBackWithTheirSeat() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var code = createRoom(host, "Ana");
    var bob = playerIn(code, "Bob");
    bob.send("{\"type\":\"joinTeam\",\"team\":\"TRIBORD\"}");
    host.await(
        "roomUpdated",
        m -> m.path("room").path("players").path(1).path("team").asString().equals("TRIBORD"));

    bob.socket.abort();
    var gone =
        host.await(
            "roomUpdated",
            m -> !m.path("room").path("players").path(1).path("connected").asBoolean());
    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(bob));

    assertThat(gone.path("room").path("players")).hasSize(2);
    assertThat(again.await("joined").path("room").path("players").path(1).path("team").asString())
        .isEqualTo("TRIBORD");
    host.await(
        "roomUpdated", m -> m.path("room").path("players").path(1).path("connected").asBoolean());
  }

  @Test
  void aPlayerWhoLeavesAGameKeepsTheSeatOffline() throws Exception {
    var players = startedGame();
    var host = players.get(0);

    players.get(3).send("{\"type\":\"leave\"}");
    host.await(
        "roomUpdated", m -> !m.path("room").path("players").path(3).path("connected").asBoolean());
    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(players.get(3)));

    assertThat(again.await("joined").path("playerId").asString())
        .isEqualTo(players.get(3).joined.path("playerId").asString());
  }

  @Test
  void theTurnPassesAtOnceWhenTheSignaleurInPlayLeaves() throws Exception {
    var players = startedGame();
    var lookout = players.get(1);
    var turn = lookout.room.path("turn").asString();
    var signaler = players.get(turn.equals("BABORD") ? 0 : 2);

    signaler.send("{\"type\":\"leave\"}");

    var update =
        lookout.await("roomUpdated", m -> !m.path("room").path("turn").asString().equals(turn));
    assertThat(update.path("room").path("phase").asString()).isEqualTo("SIGNAL");
    assertThat(update.path("room").path("turnPassesIn").isNull()).isTrue();
  }

  @Test
  void aPlayerComesBackWithTheirTokenDuringTheGame() throws Exception {
    var players = startedGame();
    var host = players.get(0);
    var lost = players.get(3);
    var playerId = lost.joined.path("playerId").asString();

    lost.socket.abort();
    host.await(
        "roomUpdated", m -> !m.path("room").path("players").path(3).path("connected").asBoolean());
    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(lost));

    var answer = again.await("joined");
    assertThat(answer.path("playerId").asString()).isEqualTo(playerId);
    assertThat(answer.path("id").asString()).isEqualTo("back");
    assertThat(
            host.await(
                "roomUpdated",
                m -> m.path("room").path("players").path(3).path("connected").asBoolean()))
        .isNotNull();
  }

  @Test
  void theAnswerCarriesTheIdOfTheMessageAndWhatGoesToTheOthersNone() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    host.send("{\"type\":\"createRoom\",\"id\":\"1\",\"name\":\"Ana\"}");
    var created = host.await("joined");
    assertThat(created.path("id").asString()).isEqualTo("1");
    var code = created.path("room").path("code").asString();

    var bob = new Client(ALLOWED_ORIGIN);
    bob.send("{\"type\":\"joinRoom\",\"id\":\"2\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");
    assertThat(bob.await("joined").path("id").asString()).isEqualTo("2");
    assertThat(host.await("roomUpdated", m -> sees(m, 2)).has("id")).isFalse();

    host.send("{\"type\":\"joinTeam\",\"id\":\"3\",\"team\":\"BABORD\"}");
    host.await("roomUpdated", m -> m.path("id").asString().equals("3"));
    var seenByBob =
        bob.await(
            "roomUpdated",
            m -> m.path("room").path("players").path(0).path("team").asString().equals("BABORD"));
    assertThat(seenByBob.has("id")).isFalse();

    host.send("{\"type\":\"start\",\"id\":\"4\"}");
    assertThat(host.await("error").path("id").asString()).isEqualTo("4");
  }

  @Test
  void aMessageWithoutAnIdIsAnsweredWithoutOne() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);

    client.send("{\"type\":\"start\"}");
    assertThat(client.await("error").has("id")).isFalse();
    client.send("{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    assertThat(client.await("joined").has("id")).isFalse();
  }

  @Test
  void aNewConnectionReplacesTheOldOneAndTheOldCloseChangesNothing() throws Exception {
    var players = startedGame();
    var host = players.get(0);
    var old = players.get(3);

    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(old));
    again.await("joined");
    eventually(() -> old.socket.isInputClosed());
    Thread.sleep(500);

    assertThat(host.player(3).path("connected").asBoolean()).isTrue();
  }

  @Test
  void theServerAnswersAnErrorAndKeepsTheConnectionOpen() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);

    client.send("{\"type\":\"nope\"}");
    assertThat(client.error()).isEqualTo("unreadable message");
    client.send("{\"type\":\"joinRoom\",\"code\":\"00000\",\"name\":\"Ana\"}");
    assertThat(client.error()).isEqualTo("no such room");
    client.send("{\"type\":\"start\"}");
    assertThat(client.error()).isEqualTo("join a room first");
    client.send("{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    client.await("joined");
    client.send("{\"type\":\"start\"}");
    assertThat(client.error()).isEqualTo("everyone must pick a team");
  }

  @Test
  void aMessageWithAMissingFieldIsRefusedAndTheConnectionStaysOpen() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);

    client.send("{\"type\":\"createRoom\"}");
    assertThat(client.error()).isEqualTo("unreadable message");
    client.send("{\"type\":\"joinRoom\",\"name\":\"Ana\"}");
    assertThat(client.error()).isEqualTo("unreadable message");
    client.send("{\"type\":\"signal\",\"number\":1}");
    assertThat(client.error()).isEqualTo("unreadable message");
    client.send("{\"type\":\"joinTeam\"}");
    assertThat(client.error()).isEqualTo("unreadable message");

    assertThat(createRoom(client, "Ana")).hasSize(5);
  }

  @Test
  void aConnectionOnlyEntersOneRoom() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);
    var code = createRoom(client, "Ana");

    client.send("{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    assertThat(client.error()).isEqualTo("you are already in a room");
    client.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Ana\"}");
    assertThat(client.error()).isEqualTo("you are already in a room");
    client.send(rejoin(client));
    assertThat(client.error()).isEqualTo("you are already in a room");
  }

  @Test
  void aGameStartsAndOnlyTheSignaleursReceiveTheKey() throws Exception {
    var players = startedGame();

    var withKey = new ArrayList<Integer>();
    for (var i = 0; i < players.size(); i++) {
      var board = players.get(i).room.path("board");
      assertThat(board).hasSize(24);
      var seesKey = board.get(0).path("kind").isString();
      board.forEach(card -> assertThat(card.path("kind").isString()).isEqualTo(seesKey));
      if (seesKey) {
        withKey.add(i);
      }
    }

    // a1 and b1 joined their team first: they are the Signaleurs for the whole grid.
    assertThat(withKey).containsExactly(0, 2);
  }

  @Test
  void aVisitorWatchesAGameInProgressAndEveryoneSeesTheCount() throws Exception {
    var players = startedGame();
    var host = players.get(0);
    var code = host.room.path("code").asString();

    var spectator = spectatorOf(code);

    assertThat(spectator.room.path("you").isNull()).isTrue();
    assertThat(spectator.room.path("phase").asString()).isEqualTo("SIGNAL");
    assertThat(spectator.history).noneMatch(m -> m.path("type").asString().equals("joined"));
    var update = host.await("roomUpdated", m -> watchedBy(m, 1));
    assertThat(update.has("id")).isFalse();
    assertThat(update.path("room").path("you").asString()).isNotBlank();

    spectator.send("{\"type\":\"leave\"}");
    host.await("roomUpdated", m -> watchedBy(m, 0));
  }

  @Test
  void aPlayerComesBackWhileSpectatorsWatch() throws Exception {
    var players = startedGame();
    var lost = players.get(3);
    spectatorOf(players.get(0).room.path("code").asString());

    lost.socket.abort();
    players
        .get(0)
        .await(
            "roomUpdated",
            m -> !m.path("room").path("players").path(3).path("connected").asBoolean());
    var again = new Client(ALLOWED_ORIGIN);
    again.send(rejoin(lost));

    assertThat(again.await("joined").path("room").path("spectators").asInt()).isEqualTo(1);
  }

  @Test
  void theKeyNeverReachesASpectatorBeforeTheEnd() throws Exception {
    var players = startedGame();
    var spectator = spectatorOf(players.get(0).room.path("code").asString());

    playTheReefsOut(players);
    spectator.await("roomUpdated", m -> m.path("room").path("phase").asString().equals("ENDED"));

    var views =
        spectator.history.stream()
            .filter(m -> m.path("type").asString().equals("roomUpdated"))
            .map(m -> m.path("room"))
            .toList();
    assertThat(views).hasSizeGreaterThan(4);
    for (var view : views) {
      var ended = view.path("phase").asString().equals("ENDED");
      assertThat(view.path("you").isNull()).isTrue();
      for (var card : view.path("board")) {
        assertThat(card.path("kind").isString())
            .as("a card of a view in phase %s", view.path("phase").asString())
            .isEqualTo(ended || card.path("revealed").asBoolean());
      }
    }
    assertThat(views.get(views.size() - 1).path("phase").asString()).isEqualTo("ENDED");
  }

  @Test
  void theServerRefusesEveryCommandOfASpectator() throws Exception {
    var players = startedGame();
    var code = players.get(0).room.path("code").asString();
    var spectator = spectatorOf(code);

    for (var command :
        List.of(
            "{\"type\":\"joinTeam\",\"id\":\"c\",\"team\":\"BABORD\"}",
            "{\"type\":\"start\",\"id\":\"c\"}",
            "{\"type\":\"signal\",\"id\":\"c\",\"word\":\"voyage\",\"number\":1}",
            "{\"type\":\"reveal\",\"id\":\"c\",\"index\":0}",
            "{\"type\":\"pass\",\"id\":\"c\"}")) {
      spectator.send(command);
      var refusal = spectator.await("error");
      assertThat(refusal.path("reason").asString()).isEqualTo("spectators cannot play");
      assertThat(refusal.path("id").asString()).isEqualTo("c");
    }
    for (var entering :
        List.of(
            "{\"type\":\"createRoom\",\"name\":\"Zoe\"}",
            "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Zoe\"}",
            "{\"type\":\"watch\",\"code\":\"" + code + "\"}",
            rejoin(players.get(3)))) {
      spectator.send(entering);
      assertThat(spectator.error()).isEqualTo("you are already in a room");
    }
  }

  @Test
  void aRoomHoldsTwentySpectators() throws Exception {
    var players = startedGame();
    var host = players.get(0);
    var code = host.room.path("code").asString();
    var spectators = new ArrayList<Client>();
    for (var i = 0; i < Room.MAX_SPECTATORS; i++) {
      spectators.add(spectatorOf(code));
    }
    host.await("roomUpdated", m -> watchedBy(m, Room.MAX_SPECTATORS));

    var late = new Client(ALLOWED_ORIGIN);
    late.send("{\"type\":\"watch\",\"code\":\"" + code + "\"}");
    assertThat(late.error()).isEqualTo("too many spectators");

    spectators.get(0).socket.abort();
    host.await("roomUpdated", m -> watchedBy(m, Room.MAX_SPECTATORS - 1));
    late.send("{\"type\":\"watch\",\"id\":\"w\",\"code\":\"" + code + "\"}");
    assertThat(late.await("roomUpdated").path("room").path("spectators").asInt())
        .isEqualTo(Room.MAX_SPECTATORS);
  }

  @Test
  void aLobbyHasNothingToWatchAndAGameInProgressStillRefusesNewPlayers() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var lobbyCode = createRoom(host, "Ana");
    var visitor = new Client(ALLOWED_ORIGIN);

    visitor.send("{\"type\":\"watch\",\"code\":\"" + lobbyCode + "\"}");
    assertThat(visitor.error()).isEqualTo("the game has not started");
    visitor.send("{\"type\":\"watch\",\"code\":\"00000\"}");
    assertThat(visitor.error()).isEqualTo("no such room");

    var code = startedGame().get(0).room.path("code").asString();
    visitor.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Zoe\"}");
    assertThat(visitor.error()).isEqualTo("the game has started");
  }

  @Test
  void theServerClosesAConnectionAtItsFifthUnknownRoom() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);
    missRooms(client, 4);

    client.send("{\"type\":\"joinRoom\",\"code\":\"00000\",\"name\":\"Ana\"}");
    eventually(() -> client.closeCode != null);

    assertThat(client.closeCode).isEqualTo(1008);
    assertThat(client.closeReason).isEqualTo("too many unknown rooms");
    assertThat(client.history)
        .filteredOn(m -> m.path("type").asString().equals("error"))
        .hasSize(4);
  }

  @Test
  void aConnectionThatFindsItsRoomStaysOpenAndTheSuccessDoesNotResetTheCount() throws Exception {
    var code = createRoom(new Client(ALLOWED_ORIGIN), "Ana");
    var client = new Client(ALLOWED_ORIGIN);
    missRooms(client, 4);

    client.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");
    client.await("joined");
    client.send("{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");
    client.await(
        "roomUpdated", m -> m.path("room").path("players").path(1).path("team").isString());
    assertThat(client.closeCode).isNull();

    client.send("{\"type\":\"watch\",\"code\":\"00000\"}");
    eventually(() -> client.closeCode != null);
    assertThat(client.closeCode).isEqualTo(1008);
  }

  @Test
  void theCountDoesNotBlockAValidRejoin() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var bob = playerIn(createRoom(host, "Ana"), "Bob");
    bob.socket.abort();
    var again = new Client(ALLOWED_ORIGIN);
    missRooms(again, 4);

    again.send(rejoin(bob));

    assertThat(again.await("joined").path("playerId").asString())
        .isEqualTo(bob.joined.path("playerId").asString());
    assertThat(again.closeCode).isNull();
  }

  @Test
  void refusesAnotherOrigin() {
    assertThatThrownBy(() -> new Client("https://evil.example"))
        .hasCauseInstanceOf(WebSocketHandshakeException.class);
  }
}
