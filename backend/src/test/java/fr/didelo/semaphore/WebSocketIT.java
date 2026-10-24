package fr.didelo.semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIT {

  private static final String ALLOWED_ORIGIN = "https://play.didelo.fr";

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Client> clients = new ArrayList<>();

  /** One player's connection: what the server sends is parsed and queued. */
  private final class Client {
    private final LinkedBlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final WebSocket socket;

    Client(String origin) {
      socket =
          http.newWebSocketBuilder()
              .header("Origin", origin)
              .buildAsync(
                  URI.create("ws://localhost:" + port + "/ws"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket ws, CharSequence data, boolean last) {
                      received.add(json.readTree(data.toString()));
                      ws.request(1);
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

    void close() {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
  }

  @AfterEach
  void closeAll() {
    clients.forEach(c -> c.socket.abort());
  }

  private String roomCode(Client host, String name) throws InterruptedException {
    host.send("{\"type\":\"createRoom\",\"name\":\"" + name + "\"}");
    return host.await("joined").path("room").path("code").asString();
  }

  private Client playerIn(String code, String name) throws InterruptedException {
    var client = new Client(ALLOWED_ORIGIN);
    client.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"" + name + "\"}");
    client.await("joined");
    return client;
  }

  private static boolean sees(JsonNode room, int players) {
    return room.path("room").path("players").size() == players;
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
    var code = roomCode(host, "Ana");

    playerIn(code, "Bob");

    assertThat(
            host.await("roomUpdated", m -> sees(m, 2))
                .path("room")
                .path("players")
                .get(1)
                .path("name")
                .asString())
        .isEqualTo("Bob");
  }

  @Test
  void aPlayerComesBackWithTheirTokenAfterALostConnection() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var code = roomCode(host, "Ana");
    var bob = new Client(ALLOWED_ORIGIN);
    bob.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");
    var joined = bob.await("joined");

    bob.close();
    host.await(
        "roomUpdated", m -> !m.path("room").path("players").path(1).path("connected").asBoolean());
    var again = new Client(ALLOWED_ORIGIN);
    again.send(
        "{\"type\":\"rejoin\",\"code\":\""
            + code
            + "\",\"token\":\""
            + joined.path("token").asString()
            + "\"}");

    assertThat(again.await("joined").path("playerId").asString())
        .isEqualTo(joined.path("playerId").asString());
    assertThat(
            host.await(
                "roomUpdated",
                m -> m.path("room").path("players").path(1).path("connected").asBoolean()))
        .isNotNull();
  }

  @Test
  void theServerAnswersAnErrorAndKeepsTheConnectionOpen() throws Exception {
    var client = new Client(ALLOWED_ORIGIN);

    client.send("{\"type\":\"nope\"}");
    assertThat(client.await("error").path("reason").asString()).isEqualTo("unreadable message");
    client.send("{\"type\":\"joinRoom\",\"code\":\"00000\",\"name\":\"Ana\"}");
    assertThat(client.await("error").path("reason").asString()).isEqualTo("no such room");
    client.send("{\"type\":\"start\"}");
    assertThat(client.await("error").path("reason").asString()).isEqualTo("join a room first");
    client.send("{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    client.await("joined");
    client.send("{\"type\":\"start\"}");
    assertThat(client.await("error").path("reason").asString())
        .isEqualTo("everyone must pick a team");
  }

  @Test
  void aGameStartsAndOnlyTheSignaleursReceiveTheKey() throws Exception {
    var host = new Client(ALLOWED_ORIGIN);
    var code = roomCode(host, "a1");
    var players = List.of(host, playerIn(code, "a2"), playerIn(code, "b1"), playerIn(code, "b2"));
    for (var i = 0; i < players.size(); i++) {
      players
          .get(i)
          .send("{\"type\":\"joinTeam\",\"team\":\"" + (i < 2 ? "BABORD" : "TRIBORD") + "\"}");
    }
    host.await(
        "roomUpdated",
        m ->
            m.path("room").path("players").findValues("team").stream().noneMatch(JsonNode::isNull)
                && sees(m, 4));

    host.send("{\"type\":\"start\"}");

    var withKey = 0;
    for (var player : players) {
      var room =
          player
              .await("roomUpdated", m -> m.path("room").path("phase").asString().equals("SIGNAL"))
              .path("room");
      assertThat(room.path("board")).hasSize(24);
      var seesKey = room.path("board").get(0).path("kind").isString();
      if (seesKey) {
        withKey++;
      }
      for (var card : room.path("board")) {
        assertThat(card.path("kind").isString()).isEqualTo(seesKey);
      }
    }
    assertThat(withKey).isEqualTo(2);
  }

  @Test
  void refusesAnotherOrigin() {
    assertThatThrownBy(() -> new Client("https://evil.example"))
        .hasCauseInstanceOf(WebSocketHandshakeException.class);
  }
}
