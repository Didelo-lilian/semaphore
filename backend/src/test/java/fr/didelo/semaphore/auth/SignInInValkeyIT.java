package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.SemaphoreApplication;
import fr.didelo.semaphore.Valkey;
import fr.didelo.semaphore.guest.Browser;
import io.lettuce.core.RedisClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two backends over one Valkey, as two replicas are: a login that starts on one ends on the other,
 * and what the provider gave is nowhere in Valkey (ADR 0011).
 */
class SignInInValkeyIT {

  private final List<ConfigurableApplicationContext> replicas = new ArrayList<>();

  @AfterEach
  void stopAll() {
    replicas.forEach(ConfigurableApplicationContext::close);
  }

  private int replica() {
    var postgres = Postgres.shared();
    var arguments =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--semaphore.redis-url=" + Valkey.url(),
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()));
    FakeProvider.properties().forEach((name, value) -> arguments.add("--" + name + "=" + value));
    var context =
        new SpringApplicationBuilder(SemaphoreApplication.class)
            .run(arguments.toArray(String[]::new));
    replicas.add(context);
    return context.getEnvironment().getProperty("local.server.port", Integer.class);
  }

  @Test
  void aLoginStartedOnOneReplicaEndsOnTheOtherAndTheProvidersTokenIsNotKept() throws Exception {
    var one = replica();
    var other = replica();
    var browser = new Browser(one);
    browser.get();
    browser.put("{\"name\":\"ana\"}");
    var start = browser.request("GET", "/api/auth/login/github");
    var login = browser.cookie("__Host-login");
    var subject = FakeProvider.newSubject();
    var callback = FakeProvider.approve(start.headers().firstValue("Location").orElseThrow());
    FakeProvider.willSignIn("github", subject);

    try (var client = RedisClient.create(Valkey.url());
        var connection = client.connect()) {
      var valkey = connection.sync();
      // In flight: JSON under the id of the cookie, for ten minutes at most.
      assertThat(valkey.get("login:" + login)).contains("\"state\"", "code_verifier");
      assertThat(valkey.ttl("login:" + login)).isBetween(590L, 600L);

      browser.use(other);
      var back = browser.request("GET", callback);

      assertThat(back.headers().firstValue("Location").orElseThrow()).endsWith("/");
      assertThat(valkey.exists("login:" + login)).isZero();
      browser.use(one);
      assertThat(browser.get().body()).contains("\"kind\":\"account\"");
      var session =
          "session:sessions:"
              + new String(Base64.getDecoder().decode(browser.cookie("__Host-session")));
      assertThat(valkey.hget(session, "sessionAttr:account.id")).isNotBlank();
      // A JWT starts with "eyJ": the access token and the ID token are nowhere, nor is a secret.
      for (var key : valkey.keys("*")) {
        var held =
            switch (valkey.type(key)) {
              case "hash" -> valkey.hgetall(key).toString();
              case "string" -> valkey.get(key);
              default -> "";
            };
        assertThat(held).as(key).doesNotContain("eyJ", "github-secret", "google-secret");
      }
    }
  }
}
