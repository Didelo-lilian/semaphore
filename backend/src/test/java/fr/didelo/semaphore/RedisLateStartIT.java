package fr.didelo.semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** A backend that starts before Redis does: it comes up, says so, and finds Redis later. */
class RedisLateStartIT {

  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void theBackendStartsHealthyWithoutRedisAndWorksOnceRedisIsThere() throws Exception {
    var port = freePort();
    try (var context =
        new SpringApplicationBuilder(SemaphoreApplication.class)
            .run("--server.port=0", "--semaphore.redis-url=" + Valkey.url(port))) {
      var server = context.getEnvironment().getProperty("local.server.port", Integer.class);
      var health =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://localhost:" + server + "/actuator/health"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body()).contains("\"UP\"").doesNotContainIgnoringCase("redis");
      var before = new GameClient(server);
      before.send("{\"type\":\"createRoom\",\"name\":\"ana\"}");
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> before.gotError("the server is busy, try again"));
      assertThat(before.closeCode()).isNull();

      try (var valkey = Valkey.container(port)) {
        valkey.start();

        var after = new GameClient(server);
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .until(
                () -> {
                  after.send("{\"type\":\"createRoom\",\"name\":\"ana\"}");
                  Thread.sleep(300);
                  return after.joined() != null;
                });
        after.abort();
      }
      before.abort();
    }
  }
}
