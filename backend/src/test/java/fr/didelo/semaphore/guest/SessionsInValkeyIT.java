package fr.didelo.semaphore.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.didelo.semaphore.GameClient;
import fr.didelo.semaphore.SemaphoreApplication;
import fr.didelo.semaphore.Valkey;
import io.lettuce.core.RedisClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Two backends over one Valkey, as two replicas are: a guest made on one is known to the other. */
class SessionsInValkeyIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<ConfigurableApplicationContext> replicas = new ArrayList<>();

  @AfterEach
  void stopAll() {
    replicas.forEach(ConfigurableApplicationContext::close);
  }

  private int replica(String url) {
    var context =
        new SpringApplicationBuilder(SemaphoreApplication.class)
            .run("--server.port=0", "--semaphore.redis-url=" + url);
    replicas.add(context);
    return context.getEnvironment().getProperty("local.server.port", Integer.class);
  }

  private static JsonNode body(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  /** The cookie holds the id of the session, in Base64. */
  private static String sessionId(Browser browser) {
    return new String(Base64.getDecoder().decode(browser.cookie("__Host-session")));
  }

  @Test
  void aGuestMadeOnOneReplicaIsKnownToTheOther() {
    var one = replica(Valkey.url());
    var other = replica(Valkey.url());
    var browser = new Browser(one);
    browser.get();
    var made = body(browser.put("{\"name\":\"ana\"}"));

    browser.use(other);
    var found = body(browser.get());

    assertThat(found.get("kind").asString()).isEqualTo("guest");
    assertThat(found.get("id").asString()).isEqualTo(made.get("id").asString());
    assertThat(found.get("name").asString()).isEqualTo("ana");
    var key = "session:sessions:" + sessionId(browser);
    try (var client = RedisClient.create(Valkey.url());
        var connection = client.connect()) {
      var valkey = connection.sync();
      assertThat(valkey.hget(key, "sessionAttr:guest.name")).isEqualTo("\"ana\"");
      assertThat(valkey.hget(key, "sessionAttr:guest.id")).contains(made.get("id").asString());
      // Seven days without use, not the thirty of the cookie.
      assertThat(valkey.ttl(key)).isBetween(7 * 86_400L - 60, 7 * 86_400L);
    }
  }

  private static String firstPlayerName(GameClient client) {
    await().atMost(Duration.ofSeconds(5)).until(() -> client.room() != null);
    return client.room().path("players").path(0).path("name").asString();
  }

  private static String cookieOf(Browser browser) {
    return "__Host-session=" + browser.cookie("__Host-session");
  }

  @Test
  void aGuestMadeOnOneReplicaNamesItsPlayerOnTheOther() {
    var one = replica(Valkey.url());
    var other = replica(Valkey.url());
    var browser = new Browser(one);
    browser.get();
    browser.put("{\"name\":\"ana\"}");

    try (var client = new GameClient(other, cookieOf(browser))) {
      client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

      assertThat(firstPlayerName(client)).isEqualTo("ana");
    }
  }

  @Test
  void aSessionThatCannotBeReadStillGivesAHandshakeAndTheNameOfTheMessage() {
    var port = replica(Valkey.url());
    try (var client = RedisClient.create(Valkey.url());
        var connection = client.connect()) {
      connection
          .sync()
          .hset(
              "session:sessions:damaged",
              Map.of("creationTime", "not json", "sessionAttr:guest.name", "\"ana\""));
      var cookie = "__Host-session=" + Base64.getEncoder().encodeToString("damaged".getBytes());

      try (var player = new GameClient(port, cookie)) {
        player.send("{\"type\":\"createRoom\",\"name\":\"bob\"}");

        assertThat(firstPlayerName(player)).isEqualTo("bob");
      }
    }
  }

  @Test
  void aHandshakeWithoutACookieCreatesNoSession() throws IOException {
    try (GenericContainer<?> valkey = Valkey.container(null)) {
      valkey.start();
      var port = replica(Valkey.url(valkey));
      var request =
          "GET /ws HTTP/1.1\r\nHost: localhost:%d\r\nOrigin: https://play.didelo.fr\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: %s\r\n\r\n"
              .formatted(port, Base64.getEncoder().encodeToString(new byte[16]));
      try (var socket = new Socket("localhost", port);
          var client = RedisClient.create(Valkey.url(valkey));
          var connection = client.connect()) {
        socket.setSoTimeout(5_000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        var status =
            new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
        assertThat(status).contains("101");

        // The session is saved when the answer is committed: give it the time it would need.
        await()
            .during(Duration.ofMillis(500))
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () -> assertThat(connection.sync().keys("session:sessions:*")).isEmpty());
      }
    }
  }

  @Test
  void withValkeyFullAKnownGuestStillGetsAHandshake() {
    try (GenericContainer<?> valkey = Valkey.container(null)) {
      valkey.start();
      var port = replica(Valkey.url(valkey));
      var browser = new Browser(port);
      browser.get();
      browser.put("{\"name\":\"ana\"}");
      try (var client = RedisClient.create(Valkey.url(valkey));
          var connection = client.connect()) {
        connection.sync().configSet("maxmemory", "1");

        try (var player = new GameClient(port, cookieOf(browser))) {
          assertThat(player.closeCode()).isNull();
        }
      }
    }
  }

  @Test
  void forgettingOnOneReplicaForgetsOnTheOther() {
    var one = replica(Valkey.url());
    var other = replica(Valkey.url());
    var browser = new Browser(one);
    browser.get();
    browser.put("{\"name\":\"ana\"}");
    var replay = new Browser(other);
    replay.get();
    replay.setCookie("__Host-session", browser.cookie("__Host-session"));
    assertThat(body(replay.get()).get("kind").asString()).isEqualTo("guest");

    browser.delete();

    assertThat(body(replay.get()).get("kind").asString()).isEqualTo("anonymous");
  }

  @Test
  void aSessionThatCannotBeReadIsNoneAndIsDropped() {
    var port = replica(Valkey.url());
    var browser = new Browser(port);
    browser.get();
    browser.setCookie("__Host-session", Base64.getEncoder().encodeToString("broken".getBytes()));
    try (var client = RedisClient.create(Valkey.url());
        var connection = client.connect()) {
      connection
          .sync()
          .hset(
              "session:sessions:broken",
              Map.of("creationTime", "not json", "sessionAttr:guest.name", "\"ana\""));

      var response = browser.get();

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(body(response).get("kind").asString()).isEqualTo("anonymous");
      assertThat(connection.sync().exists("session:sessions:broken")).isZero();
    }
  }

  @Test
  void withValkeyFullAGuestIsToldTheServerIsFull() {
    try (GenericContainer<?> valkey = Valkey.container(null)) {
      valkey.start();
      var port = replica(Valkey.url(valkey));
      var browser = new Browser(port);
      browser.get();
      try (var client = RedisClient.create(Valkey.url(valkey));
          var connection = client.connect()) {
        connection.sync().configSet("maxmemory", "1");

        var response = browser.put("{\"name\":\"ana\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body(response).get("error").asString())
            .isEqualTo("the server is full, try again later");
        assertThat(browser.get().statusCode()).isEqualTo(200);
      }
    }
  }

  @Test
  void withValkeyGoneAGuestAnswersBusyAndTheBackendStaysHealthy() throws Exception {
    try (GenericContainer<?> valkey = Valkey.container(null)) {
      valkey.start();
      var port = replica(Valkey.url(valkey));
      var browser = new Browser(port);
      browser.get();
      assertThat(browser.put("{\"name\":\"ana\"}").statusCode()).isEqualTo(200);

      valkey.stop();

      var known = browser.get();
      assertThat(known.statusCode()).isEqualTo(503);
      assertThat(body(known).get("error").asString()).isEqualTo("the server is busy, try again");
      var visitor = new Browser(port);
      assertThat(visitor.get().statusCode()).isEqualTo(200);
      var creating = visitor.put("{\"name\":\"bea\"}");
      assertThat(creating.statusCode()).isEqualTo(503);
      assertThat(body(creating).get("error").asString()).isEqualTo("the server is busy, try again");
      // A WebSocket handshake that carries the cookie must not fail on a session it cannot read.
      var socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .header("Origin", "https://play.didelo.fr")
              .header("Cookie", "__Host-session=" + browser.cookie("__Host-session"))
              .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {})
              .get(5, TimeUnit.SECONDS);
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
      var health =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://localhost:" + port + "/actuator/health"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
    }
  }
}
