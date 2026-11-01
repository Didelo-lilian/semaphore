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

  @Test
  void aGuestIsNamedByItsSessionWhateverTheMessageAsks() {
    var client = new GameClient(port, guestCookie("ana"));

    client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

    assertThat(firstPlayerName(client)).isEqualTo("ana");
  }

  @Test
  void aBrowserWithoutASessionIsNamedAsItAsks() {
    var client = new GameClient(port);

    client.send("{\"type\":\"createRoom\",\"name\":\"bob\"}");

    assertThat(firstPlayerName(client)).isEqualTo("bob");
  }

  @Test
  void aGuestWhoJoinsIsNamedByItsSessionToo() {
    var host = new GameClient(port);
    host.send("{\"type\":\"createRoom\",\"name\":\"host\"}");
    var code = codeOf(host);
    var guest = new GameClient(port, guestCookie("bea"));

    guest.send("{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"mallory\"}");

    await().atMost(Duration.ofSeconds(5)).until(() -> guest.room() != null);
    var names = guest.room().path("players");
    assertThat(names.get(0).path("name").asString()).isEqualTo("host");
    assertThat(names.get(1).path("name").asString()).isEqualTo("bea");
  }

  @Test
  void aClientCannotSayWhichAccountItIs() {
    var client = new GameClient(port);

    client.send("{\"type\":\"createRoom\",\"name\":\"eve\",\"accountId\":\"someone-else\"}");

    assertThat(rooms.find(codeOf(client)).orElseThrow().state().players().getFirst().accountId())
        .isNull();
  }

  @Test
  void theAccountOfTheSessionReachesThePlayer() {
    var account = UUID.randomUUID();
    var session = sessions.createSession();
    session.setAttribute(Identity.NAME, "zoe");
    session.setAttribute(Identity.ACCOUNT_ID, account.toString());
    sessions.save(session);
    var cookie = "__Host-session=" + Base64.getEncoder().encodeToString(session.getId().getBytes());
    var client = new GameClient(port, cookie);

    client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");

    assertThat(firstPlayerName(client)).isEqualTo("zoe");
    assertThat(rooms.find(codeOf(client)).orElseThrow().state().players().getFirst().accountId())
        .isEqualTo(account);
  }
}
