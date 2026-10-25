package fr.didelo.semaphore.guest;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.SemaphoreApplication;
import fr.didelo.semaphore.Valkey;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
    try (var client = RedisClient.create(Valkey.url());
        var connection = client.connect()) {
      assertThat(connection.sync().keys("session:*")).isNotEmpty();
    }
  }

  @Test
  void forgettingOnOneReplicaForgetsOnTheOther() {
    var one = replica(Valkey.url());
    var other = replica(Valkey.url());
    var browser = new Browser(one);
    browser.get();
    browser.put("{\"name\":\"ana\"}");
    var cookie = browser.cookie("__Host-session");

    browser.delete();
    var replay = new Browser(other);
    replay.get();
    replay.setCookie("__Host-session", cookie);
    var response = replay.get();

    assertThat(body(response).get("kind").asString()).isEqualTo("anonymous");
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
