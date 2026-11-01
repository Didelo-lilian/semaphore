package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.guest.Browser;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A provider that takes the connection and never answers does not hold the login for ever. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInTimeoutIT {

  // The system accepts the connection into its backlog and nobody ever reads it.
  private static final ServerSocket SILENT = silent();

  private static ServerSocket silent() {
    try {
      return new ServerSocket(0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    Postgres.connect(registry, Postgres.shared());
    FakeProvider.properties().forEach((name, value) -> registry.add(name, () -> value));
    registry.add(
        "semaphore.auth.github.token-uri",
        () -> "http://localhost:" + SILENT.getLocalPort() + "/token");
  }

  @AfterAll
  static void close() throws IOException {
    SILENT.close();
  }

  @LocalServerPort private int port;

  @Test
  void theCodeExchangeGivesUpAfterFiveSecondsAndTheLoginFails() {
    var browser = new Browser(port);
    browser.get();
    browser.put("{\"name\":\"ana\"}");
    var start = browser.request("GET", "/api/auth/login/github");
    var state =
        FakeProvider.parameters(start.headers().firstValue("Location").orElseThrow()).get("state");

    var began = System.nanoTime();
    var back = browser.request("GET", "/api/auth/callback/github?code=any&state=" + state);
    var took = Duration.ofNanos(System.nanoTime() - began);

    assertThat(back.headers().firstValue("Location").orElseThrow()).endsWith("/?login=failed");
    assertThat(took).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(9));
  }
}
