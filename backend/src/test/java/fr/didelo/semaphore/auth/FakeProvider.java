package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * GitHub and Google as one fake provider in the JVM (ADR 0011): the tests never reach a real one.
 * It answers as GitHub does for a registration without {@code openid}, with the account's id in the
 * user-info, and as Google does for one with it, with the id in the ID token.
 */
final class FakeProvider {

  static final MockOAuth2Server SERVER = new MockOAuth2Server();

  private static final HttpClient BROWSER = HttpClient.newHttpClient();

  static {
    SERVER.start();
    Runtime.getRuntime().addShutdownHook(new Thread(SERVER::shutdown));
  }

  private FakeProvider() {}

  /** The settings of both providers, pointed at the fake. */
  static Map<String, String> properties() {
    var settings = new LinkedHashMap<String, String>();
    for (var name : List.of("github", "google")) {
      var prefix = "semaphore.auth." + name + ".";
      settings.put(prefix + "client-id", name + "-client");
      settings.put(prefix + "client-secret", name + "-secret");
      settings.put(
          prefix + "authorization-uri", SERVER.authorizationEndpointUrl("default").toString());
      settings.put(prefix + "token-uri", SERVER.tokenEndpointUrl("default").toString());
      settings.put(prefix + "user-info-uri", SERVER.userInfoUrl("default").toString());
    }
    settings.put("semaphore.auth.google.jwk-set-uri", SERVER.jwksUrl("default").toString());
    settings.put("semaphore.auth.google.issuer-uri", SERVER.issuerUrl("default").toString());
    return settings;
  }

  /** An id as a provider gives them: a number that is not a counter. */
  static String newSubject() {
    return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE));
  }

  /** The next code exchange is for the player whose id at {@code provider} is {@code subject}. */
  static void willSignIn(String provider, String subject) {
    var claims =
        provider.equals("github")
            ? Map.<String, Object>of("id", Long.parseLong(subject))
            : Map.<String, Object>of();
    SERVER.enqueueCallback(
        new DefaultOAuth2TokenCallback(
            "default", subject, "JWT", List.of(provider + "-client"), claims, 3600));
  }

  /**
   * The player consents at the provider: what the browser asks for next, the path of the callback.
   * The fake only knows OpenID requests, so GitHub's, which has no scope, is asked for {@code
   * openid} here; what the backend sent (state, PKCE challenge) is otherwise unchanged.
   */
  static String approve(String authorizationUrl) throws Exception {
    var asked =
        UriComponentsBuilder.fromUriString(authorizationUrl)
            .replaceQueryParam("scope", "openid")
            .build(true)
            .toUriString();
    var response =
        BROWSER.send(
            HttpRequest.newBuilder(URI.create(asked)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
    var callback = URI.create(response.headers().firstValue("Location").orElseThrow());
    return callback.getRawPath() + "?" + callback.getRawQuery();
  }

  /**
   * The form of the last code exchange the fake received, as parameters; none if there was none.
   */
  static Map<String, String> lastCodeExchange() throws InterruptedException {
    String form = null;
    try {
      while (true) {
        var request = SERVER.takeRequest(200, TimeUnit.MILLISECONDS);
        if (request.getPath().endsWith("/token")) {
          form = request.getBody().readUtf8();
        }
      }
    } catch (RuntimeException drained) {
      // The fake throws, instead of returning null, when no request is left.
    }
    return form == null ? Map.of() : parameters("http://provider.test/token?" + form);
  }

  /** The decoded parameters of a URL. */
  static Map<String, String> parameters(String url) {
    var parameters = new LinkedHashMap<String, String>();
    UriComponentsBuilder.fromUriString(url)
        .build()
        .getQueryParams()
        .forEach(
            (name, values) ->
                parameters.put(name, UriUtils.decode(values.getFirst(), StandardCharsets.UTF_8)));
    return parameters;
  }
}
