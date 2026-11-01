package fr.didelo.semaphore.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.didelo.semaphore.GameClient;
import fr.didelo.semaphore.game.RoomRegistry;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.MapSessionRepository;

/** The handshake reads who the session says the browser is; a message never does. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuestIdentityIT {

  @LocalServerPort private int port;
  @Autowired private RoomRegistry rooms;
  @Autowired private MapSessionRepository sessions;

  private String guestCookie(String name) {
    var browser = new Browser(port);
    browser.get();
    browser.put("{\"name\":\"" + name + "\"}");
    return "__Host-session=" + browser.cookie("__Host-session");
  }

  private static String firstPlayerName(GameClient client) {
    await().atMost(Duration.ofSeconds(5)).until(() -> client.room() != null);
    return client.room().path("players").path(0).path("name").asString();
  }

  private static String codeOf(GameClient client) {
    await().atMost(Duration.ofSeconds(5)).until(() -> client.room() != null);
    return client.room().path("code").asString();
  }

  private String sessionCookie(String name, Object account) {
    var session = sessions.createSession();
    session.setAttribute(Identity.NAME, name);
    session.setAttribute(Identity.ACCOUNT_ID, account);
    sessions.save(session);
    return "__Host-session=" + Base64.getEncoder().encodeToString(session.getId().getBytes());
  }

  private UUID accountOf(GameClient client) {
    return rooms.find(codeOf(client)).orElseThrow().state().players().getFirst().accountId();
  }

  @Test
  void aGuestIsNamedByItsSessionWhateverTheMessageAsks() {
    try (var client = new GameClient(port, guestCookie("ana"))) {
      client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

      assertThat(firstPlayerName(client)).isEqualTo("ana");
    }
  }

  @Test
  void aBrowserWithoutASessionIsNamedAsItAsks() {
    try (var client = new GameClient(port)) {
      client.send("{\"type\":\"createRoom\",\"name\":\"bob\"}");

      assertThat(firstPlayerName(client)).isEqualTo("bob");
    }
  }

  @Test
  void aGuestWhoJoinsIsNamedByItsSessionToo() {
    try (var host = new GameClient(port)) {
      host.send("{\"type\":\"createRoom\",\"name\":\"host\"}");
      var code = codeOf(host);
      try (var guest = new GameClient(port, guestCookie("bea"))) {
        guest.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"mallory\"}");

        await().atMost(Duration.ofSeconds(5)).until(() -> guest.room() != null);
        var names = guest.room().path("players");
        assertThat(names.get(0).path("name").asString()).isEqualTo("host");
        assertThat(names.get(1).path("name").asString()).isEqualTo("bea");
      }
    }
  }

  @Test
  void aClientCannotSayWhichAccountItIs() {
    try (var client = new GameClient(port)) {
      client.send("{\"type\":\"createRoom\",\"name\":\"eve\",\"accountId\":\"someone-else\"}");

      assertThat(accountOf(client)).isNull();
    }
  }

  @Test
  void theAccountOfTheSessionReachesThePlayerAsValkeyHoldsIt() {
    var account = UUID.randomUUID();
    try (var client = new GameClient(port, sessionCookie("zoe", account.toString()))) {
      client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

      assertThat(firstPlayerName(client)).isEqualTo("zoe");
      assertThat(accountOf(client)).isEqualTo(account);
    }
  }

  @Test
  void theAccountOfTheSessionReachesThePlayerAsTheMemoryHoldsIt() {
    var account = UUID.randomUUID();
    try (var client = new GameClient(port, sessionCookie("zoe", account))) {
      client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

      assertThat(accountOf(client)).isEqualTo(account);
    }
  }
}
