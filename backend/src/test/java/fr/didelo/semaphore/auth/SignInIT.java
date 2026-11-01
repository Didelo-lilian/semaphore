package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.guest.Browser;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
  void aWrongStateIsRefusedAndNothingIsCreated() throws Exception {
    var browser = guest("ana");
    browser.request("GET", "/api/auth/login/github");

    var back = browser.request("GET", "/api/auth/callback/github?code=any&state=forged");

    assertThat(landing(back)).isEqualTo("/?login=failed");
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("guest");
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
  void aGuestHasNoAccountToDelete() {
    assertThat(guest("ana").request("DELETE", "/api/auth/account").statusCode()).isEqualTo(401);
  }
}
