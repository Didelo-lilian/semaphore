package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import fr.didelo.semaphore.GameClient;
import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Unavailable;
import fr.didelo.semaphore.guest.Browser;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Both providers on, against the fake provider and a real Postgres: ADR 0011 end to end. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    Postgres.connect(registry, Postgres.shared());
    FakeProvider.properties().forEach((name, value) -> registry.add(name, () -> value));
  }

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private RoomRegistry rooms;

  // Spies: they do what the real beans do until a test makes a store fail.
  @MockitoSpyBean private LoginRequests requests;
  @MockitoSpyBean private MapSessionRepository sessions;
  @MockitoSpyBean private Accounts accounts;

  private static JsonNode body(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  /** Where a redirect leads on this server: Tomcat makes the address absolute. */
  private static String landing(HttpResponse<String> response) {
    var uri = URI.create(location(response));
    return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  private MapSession sessionOf(Browser browser) {
    return sessions.findById(
        new String(Base64.getDecoder().decode(browser.cookie("__Host-session"))));
  }

  private Browser guest(String pseudo) {
    var browser = new Browser(port);
    browser.get();
    browser.put("{\"name\":\"" + pseudo + "\"}");
    return browser;
  }

  /** The whole round trip of a player who consents: the answer of the callback. */
  private HttpResponse<String> signIn(Browser browser, String provider, String subject)
      throws Exception {
    var start = browser.request("GET", "/api/auth/login/" + provider);
    var callback = FakeProvider.approve(location(start));
    FakeProvider.willSignIn(provider, subject);
    var back = browser.request("GET", callback);
    assertThat(landing(back)).isEqualTo("/");
    return back;
  }

  private List<String> accountsOf(String provider, String subject) {
    return jdbc.sql("SELECT display_name FROM account WHERE provider = :p AND subject = :s")
        .param("p", provider)
        .param("s", subject)
        .query(String.class)
        .list();
  }

  @Test
  void aLoginSendsTheBrowserToGoogleWithStateNonceAndPkce() {
    var browser = guest("ana");

    var response = browser.request("GET", "/api/auth/login/google");

    assertThat(response.statusCode()).isEqualTo(302);
    var url = location(response);
    assertThat(url).startsWith(FakeProvider.SERVER.authorizationEndpointUrl("default").toString());
    var parameters = FakeProvider.parameters(url);
    assertThat(parameters)
        .containsEntry("response_type", "code")
        .containsEntry("client_id", "google-client")
        .containsEntry("scope", "openid")
        .containsEntry("code_challenge_method", "S256")
        .containsKeys("state", "nonce", "code_challenge")
        .containsEntry("redirect_uri", "http://localhost:" + port + "/api/auth/callback/google");
    var cookie = browser.setCookies.stream().filter(c -> c.startsWith("__Host-login=")).findFirst();
    assertThat(cookie).isPresent();
    assertThat(cookie.get())
        .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=600")
        .doesNotContain("Domain");
  }

  @Test
  void aLoginSendsTheBrowserToGithubWithNoScope() {
    var response = guest("ana").request("GET", "/api/auth/login/github");

    var parameters = FakeProvider.parameters(location(response));
    assertThat(parameters)
        .containsEntry("client_id", "github-client")
        .containsEntry("code_challenge_method", "S256")
        .containsKeys("state", "code_challenge")
        .doesNotContainKeys("scope", "nonce");
  }

  @Test
  void aVisitorWhoChoseNoPseudoIsSentBackAndHoldsNoLoginCookie() {
    var browser = new Browser(port);
    browser.get();

    var response = browser.request("GET", "/api/auth/login/github");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(landing(response)).isEqualTo("/?login=pseudo");
    assertThat(browser.cookie("__Host-login")).isNull();
  }

  @Test
  void anUnknownProviderIsNotFound() {
    var browser = guest("ana");

    assertThat(browser.request("GET", "/api/auth/login/gitlab").statusCode()).isEqualTo(404);
    assertThat(browser.request("GET", "/api/auth/callback/gitlab?code=a&state=b").statusCode())
        .isEqualTo(404);
  }

  @Test
  void githubSignsAnAccountInAndTheSessionChangesItsId() throws Exception {
    var browser = guest("ana");
    var before = browser.cookie("__Host-session");
    var subject = FakeProvider.newSubject();

    var back = signIn(browser, "github", subject);

    assertThat(back.statusCode()).isEqualTo(302);
    assertThat(landing(back)).isEqualTo("/");
    assertThat(browser.cookie("__Host-session")).isNotBlank().isNotEqualTo(before);
    assertThat(browser.cookie("__Host-login")).isNull();
    var me = body(browser.get());
    assertThat(me.get("kind").asString()).isEqualTo("account");
    assertThat(me.get("name").asString()).isEqualTo("ana");
    assertThat(me.has("providers")).isFalse();
    assertThat(accountsOf("github", subject)).containsExactly("ana");
    var stored =
        jdbc.sql("SELECT id FROM account WHERE provider = 'github' AND subject = :s")
            .param("s", subject)
            .query(UUID.class)
            .single();
    assertThat(me.get("id").asString()).isEqualTo(stored.toString());
    var replay = new Browser(port);
    replay.get();
    replay.setCookie("__Host-session", before);
    assertThat(body(replay.get()).get("kind").asString()).isEqualTo("anonymous");
  }

  @Test
  void theCodeIsExchangedWithTheVerifierOfTheChallengeThatWasSent() throws Exception {
    var browser = guest("ana");
    var start = browser.request("GET", "/api/auth/login/github");
    var challenge = FakeProvider.parameters(location(start)).get("code_challenge");
    var callback = FakeProvider.approve(location(start));
    FakeProvider.willSignIn("github", FakeProvider.newSubject());
    browser.request("GET", callback);

    var exchange = FakeProvider.lastCodeExchange();

    assertThat(exchange).containsEntry("grant_type", "authorization_code").containsKey("code");
    var digest =
        MessageDigest.getInstance("SHA-256")
            .digest(exchange.get("code_verifier").getBytes(StandardCharsets.US_ASCII));
    assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(digest)).isEqualTo(challenge);
  }

  @Test
  void googleSignsAnAccountInWithTheIdOfItsIdToken() throws Exception {
    var browser = guest("ana");
    var subject = FakeProvider.newSubject();

    var back = signIn(browser, "google", subject);

    assertThat(landing(back)).isEqualTo("/");
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("account");
    assertThat(accountsOf("google", subject)).containsExactly("ana");
  }

  @Test
  void oneIdentityIsOneAccountAndTheNameOfTheAccountWins() throws Exception {
    var subject = FakeProvider.newSubject();
    var first = guest("ana");
    signIn(first, "google", subject);
    var second = guest("bob");

    signIn(second, "google", subject);

    var one = body(first.get());
    var other = body(second.get());
    assertThat(other.get("id").asString()).isEqualTo(one.get("id").asString());
    assertThat(other.get("name").asString()).isEqualTo("ana");
    assertThat(accountsOf("google", subject)).hasSize(1);
  }

  @Test
  void theSameIdAtTwoProvidersIsTwoAccounts() throws Exception {
    var subject = FakeProvider.newSubject();

    signIn(guest("ana"), "github", subject);
    signIn(guest("ana"), "google", subject);

    assertThat(accountsOf("github", subject)).hasSize(1);
    assertThat(accountsOf("google", subject)).hasSize(1);
  }

  @Test
  void aWrongStateIsRefusedBeforeTheCodeIsExchangedAndNothingIsCreated() throws Exception {
    var browser = guest("ana");
    var start = browser.request("GET", "/api/auth/login/github");
    // A code the provider really issued, for the real state: only the state is forged.
    var callback = FakeProvider.approve(location(start));
    var forged = callback.replaceFirst("state=[^&]+", "state=forged");
    FakeProvider.lastCodeExchange();

    var back = browser.request("GET", forged);

    assertThat(landing(back)).isEqualTo("/?login=failed");
    assertThat(FakeProvider.lastCodeExchange()).isEmpty();
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("guest");
  }

  @Test
  void aBrowserThatStartsAgainLeavesNoEarlierLoginBehind() {
    var browser = guest("ana");
    browser.request("GET", "/api/auth/login/github");
    var first = browser.cookie("__Host-login");
    assertThat(requests.get(first)).isPresent();

    browser.request("GET", "/api/auth/login/google");

    assertThat(browser.cookie("__Host-login")).isNotEqualTo(first);
    assertThat(requests.get(first)).isEmpty();
    assertThat(requests.get(browser.cookie("__Host-login"))).isPresent();
  }

  @Test
  void aSessionThatHasAnAccountDoesNotStartAnotherLogin() throws Exception {
    var subject = FakeProvider.newSubject();
    var browser = guest("ana");
    signIn(browser, "github", subject);
    var account = body(browser.get()).get("id").asString();

    var response = browser.request("GET", "/api/auth/login/google");

    assertThat(landing(response)).isEqualTo("/");
    assertThat(browser.cookie("__Host-login")).isNull();
    assertThat(body(browser.get()).get("id").asString()).isEqualTo(account);
    assertThat(accountsOf("github", subject)).hasSize(1);
  }

  @Test
  void twoFirstLoginsOfOneIdentityAtOnceMakeOneAccount() throws Exception {
    var subject = FakeProvider.newSubject();
    var browsers = List.of(guest("ana"), guest("bob"));
    var callbacks = new ArrayList<String>();
    for (var browser : browsers) {
      callbacks.add(
          FakeProvider.approve(location(browser.request("GET", "/api/auth/login/github"))));
      FakeProvider.willSignIn("github", subject);
    }
    var go = new CountDownLatch(1);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var backs =
          IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            go.await();
                            return landing(browsers.get(i).request("GET", callbacks.get(i)));
                          }))
              .toList();
      go.countDown();
      for (var back : backs) {
        assertThat(back.get(10, TimeUnit.SECONDS)).isEqualTo("/");
      }
    }

    assertThat(accountsOf("github", subject)).hasSize(1);
  }

  @Test
  void aRefusedConsentLandsOnFailed() {
    var browser = guest("ana");
    var start = browser.request("GET", "/api/auth/login/github");
    var state = FakeProvider.parameters(location(start)).get("state");

    var back =
        browser.request("GET", "/api/auth/callback/github?error=access_denied&state=" + state);

    assertThat(landing(back)).isEqualTo("/?login=failed");
  }

  @Test
  void aCallbackWithoutTheLoginCookieFails() throws Exception {
    var browser = guest("ana");
    var start = browser.request("GET", "/api/auth/login/github");
    var callback = FakeProvider.approve(location(start));
    var stranger = new Browser(port);
    stranger.get();
    stranger.setCookie("__Host-session", browser.cookie("__Host-session"));

    var back = stranger.request("GET", callback);

    assertThat(landing(back)).isEqualTo("/?login=failed");
  }

  @Test
  void aLoginIsUsedOnce() throws Exception {
    var browser = guest("ana");
    var start = browser.request("GET", "/api/auth/login/github");
    var login = browser.cookie("__Host-login");
    var callback = FakeProvider.approve(location(start));
    FakeProvider.willSignIn("github", FakeProvider.newSubject());
    assertThat(landing(browser.request("GET", callback))).isEqualTo("/");

    browser.setCookie("__Host-login", login);
    var replayed = browser.request("GET", callback);

    assertThat(landing(replayed)).isEqualTo("/?login=failed");
  }

  @Test
  void aSessionThatEndedAtTheProviderAsksForAPseudoAgain() throws Exception {
    var browser = guest("ana");
    var subject = FakeProvider.newSubject();
    var start = browser.request("GET", "/api/auth/login/github");
    var callback = FakeProvider.approve(location(start));
    FakeProvider.willSignIn("github", subject);
    browser.delete();

    var back = browser.request("GET", callback);

    assertThat(landing(back)).isEqualTo("/?login=pseudo");
    assertThat(accountsOf("github", subject)).isEmpty();
  }

  @Test
  void aGuestAndAnAnonymousVisitorAreOfferedTheProvidersAndAnAccountIsNot() throws Exception {
    var anonymous = new Browser(port);

    assertThat(body(anonymous.get()).get("providers").valueStream().map(JsonNode::asString))
        .containsExactly("github", "google");
    var browser = guest("ana");
    assertThat(body(browser.get()).get("providers").valueStream().map(JsonNode::asString))
        .containsExactly("github", "google");
    signIn(browser, "github", FakeProvider.newSubject());
    assertThat(body(browser.get()).has("providers")).isFalse();
  }

  @Test
  void renamingAnAccountRenamesItForEverySessionOfIt() throws Exception {
    var subject = FakeProvider.newSubject();
    var first = guest("ana");
    signIn(first, "github", subject);
    var second = guest("bob");
    signIn(second, "github", subject);

    var renamed = body(first.put("{\"name\":\"zoe\"}"));

    assertThat(renamed.get("kind").asString()).isEqualTo("account");
    assertThat(renamed.get("name").asString()).isEqualTo("zoe");
    assertThat(accountsOf("github", subject)).containsExactly("zoe");
    assertThat(body(second.get()).get("name").asString()).isEqualTo("zoe");
    // The name a room reads at the handshake is the session's: it follows the account.
    assertThat(sessionOf(second).<String>getAttribute("guest.name")).isEqualTo("zoe");
  }

  @Test
  void anAccountSessionStopsCountingAsOneAfterTheLifeOfTheCookie() throws Exception {
    var subject = FakeProvider.newSubject();
    var browser = guest("ana");
    signIn(browser, "github", subject);
    var session = sessionOf(browser);
    var longAgo = Instant.now().minus(31, ChronoUnit.DAYS).getEpochSecond();
    session.setAttribute("account.at", Long.toString(longAgo));
    sessions.save(session);

    assertThat(browser.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(401);
    assertThat(accountsOf("github", subject)).hasSize(1);
    var me = body(browser.get());
    assertThat(me.get("kind").asString()).isEqualTo("guest");
    assertThat(me.get("name").asString()).isEqualTo("ana");
  }

  /** The account the first player of the room that {@code browser} creates sits with, or null. */
  private UUID accountInARoomCreatedBy(Browser browser) {
    try (var client = new GameClient(port, "__Host-session=" + browser.cookie("__Host-session"))) {
      client.send("{\"type\":\"createRoom\",\"name\":\"mallory\"}");
      await().atMost(Duration.ofSeconds(5)).until(() -> client.room() != null);
      return rooms
          .find(client.room().path("code").asString())
          .orElseThrow()
          .state()
          .players()
          .getFirst()
          .accountId();
    }
  }

  @Test
  void aSignedInPlayerSitsInARoomWithItsAccount() throws Exception {
    var browser = guest("ana");
    signIn(browser, "github", FakeProvider.newSubject());

    assertThat(accountInARoomCreatedBy(browser).toString())
        .isEqualTo(body(browser.get()).get("id").asString());
  }

  @Test
  void aLoginOlderThanTheCookieSitsInARoomAsAGuest() throws Exception {
    var browser = guest("ana");
    signIn(browser, "github", FakeProvider.newSubject());
    var session = sessionOf(browser);
    session.setAttribute(
        "account.at", Long.toString(Instant.now().minus(31, ChronoUnit.DAYS).getEpochSecond()));
    sessions.save(session);

    assertThat(accountInARoomCreatedBy(browser)).isNull();
  }

  @Test
  void aLoginThatValkeyCannotKeepSendsTheBrowserHome() {
    var browser = guest("ana");
    doThrow(new Unavailable("the server is busy, try again")).when(requests).put(any(), any());

    var response = browser.request("GET", "/api/auth/login/github");

    assertThat(landing(response)).isEqualTo("/?login=failed");
  }

  @Test
  void aCallbackThatCannotReadItsLoginSendsTheBrowserHome() throws Exception {
    var browser = guest("ana");
    var callback = FakeProvider.approve(location(browser.request("GET", "/api/auth/login/github")));
    doThrow(new Unavailable("the server is busy, try again")).when(requests).take(any());

    assertThat(landing(browser.request("GET", callback))).isEqualTo("/?login=failed");
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("guest");
  }

  @Test
  void aSessionStoreThatIsOutSendsALoginHomeWhileTheApiAnswersBusy() {
    var browser = guest("ana");
    doThrow(new QueryTimeoutException("out")).when(sessions).findById(any());

    assertThat(landing(browser.request("GET", "/api/auth/login/github")))
        .isEqualTo("/?login=failed");
    assertThat(browser.get().statusCode()).isEqualTo(503);
  }

  @Test
  void aDatabaseThatIsOutEndsALoginOnFailed() throws Exception {
    var browser = guest("ana");
    var callback = FakeProvider.approve(location(browser.request("GET", "/api/auth/login/github")));
    FakeProvider.willSignIn("github", FakeProvider.newSubject());
    doThrow(new DataAccessResourceFailureException("down"))
        .when(accounts)
        .findOrCreate(any(), any(), any());

    assertThat(landing(browser.request("GET", callback))).isEqualTo("/?login=failed");
  }

  @Test
  void anAccountIsStillAnAccountWhileTheDatabaseIsOut() throws Exception {
    var browser = guest("ana");
    signIn(browser, "github", FakeProvider.newSubject());
    var id = body(browser.get()).get("id").asString();
    doThrow(new DataAccessResourceFailureException("down")).when(accounts).find(any());

    var me = body(browser.get());

    assertThat(me.get("kind").asString()).isEqualTo("account");
    assertThat(me.get("id").asString()).isEqualTo(id);
    assertThat(me.get("name").asString()).isEqualTo("ana");
  }

  @Test
  void loggingOutEndsTheSessionAndNeedsTheToken() throws Exception {
    var browser = guest("ana");
    signIn(browser, "github", FakeProvider.newSubject());
    var forged = new Browser(port);
    forged.setCookie("__Host-session", browser.cookie("__Host-session"));

    assertThat(forged.request("POST", "/api/auth/logout").statusCode()).isEqualTo(403);
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("account");
    assertThat(browser.request("POST", "/api/auth/logout").statusCode()).isEqualTo(204);

    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("anonymous");
  }

  @Test
  void deletingTheAccountDeletesTheRowAndTheOtherBrowsersLearnItAtTheirNextCall() throws Exception {
    var subject = FakeProvider.newSubject();
    var first = guest("ana");
    signIn(first, "github", subject);
    var second = guest("bob");
    signIn(second, "github", subject);

    assertThat(first.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(204);

    assertThat(accountsOf("github", subject)).isEmpty();
    assertThat(body(first.get()).get("kind").asString()).isEqualTo("anonymous");
    var left = body(second.get());
    assertThat(left.get("kind").asString()).isEqualTo("guest");
    assertThat(left.get("name").asString()).isEqualTo("ana");
  }

  @Test
  void deletingTheAccountForgetsItsGamesAndDropsTheOnesNobodyIsLeftIn() throws Exception {
    var ana = guest("ana");
    signIn(ana, "github", FakeProvider.newSubject());
    var bob = guest("bob");
    signIn(bob, "github", FakeProvider.newSubject());
    var game = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO game (id, ended_at, winner, end_reason) VALUES (?, now(), 'BABORD', 'COMPLETE')")
        .param(game)
        .update();
    for (var player : List.of(ana, bob)) {
      var me = body(player.get());
      jdbc.sql(
              "INSERT INTO game_player (game_id, player_id, name, team, role, account_id) VALUES (?, ?, ?, 'BABORD', 'SIGNALER', ?)")
          .params(
              game,
              me.get("id").asString(),
              me.get("name").asString(),
              UUID.fromString(me.get("id").asString()))
          .update();
    }

    assertThat(ana.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(204);

    assertThat(
            jdbc.sql("SELECT name FROM game_player WHERE game_id = ? ORDER BY name")
                .param(game)
                .query(String.class)
                .list())
        .containsExactly("a former player", "bob");
    assertThat(bob.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(204);
    assertThat(
            jdbc.sql("SELECT count(*) FROM game WHERE id = ?")
                .param(game)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void deletingTheAccountNeedsTheToken() throws Exception {
    var subject = FakeProvider.newSubject();
    var browser = guest("ana");
    signIn(browser, "github", subject);
    var forged = new Browser(port);
    forged.setCookie("__Host-session", browser.cookie("__Host-session"));

    assertThat(forged.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(403);
    assertThat(accountsOf("github", subject)).hasSize(1);
    assertThat(browser.request("DELETE", "/api/auth/account").statusCode()).isEqualTo(204);
  }

  @Test
  void aLoginIsStartedByGetAndAnythingElseNeedsTheToken() {
    var browser = guest("ana");
    var forged = new Browser(port);
    forged.setCookie("__Host-session", browser.cookie("__Host-session"));

    assertThat(forged.request("POST", "/api/auth/login/github").statusCode()).isEqualTo(403);
    assertThat(forged.setCookies).noneMatch(cookie -> cookie.startsWith("__Host-login="));
    assertThat(browser.request("GET", "/api/auth/login/github").statusCode()).isEqualTo(302);
  }

  @Test
  void aGuestHasNoAccountToDelete() {
    assertThat(guest("ana").request("DELETE", "/api/auth/account").statusCode()).isEqualTo(401);
  }
}
