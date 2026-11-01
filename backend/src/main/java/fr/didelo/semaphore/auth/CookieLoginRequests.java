package fr.didelo.semaphore.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the authorization request of a login (its {@code state}, the PKCE verifier, the nonce) in a
 * {@link LoginRequests} under a random id that the browser carries in the cookie {@code
 * __Host-login}, as JSON and never as a Java object (ADR 0011). {@code Lax} is what lets the cookie
 * come back from the provider.
 */
@Component
@WithSignIn
@RequiredArgsConstructor
class CookieLoginRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  static final String COOKIE = "__Host-login";

  private static final Base64StringKeyGenerator IDS =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

  record Stored(
      String authorizationUri,
      String clientId,
      String redirectUri,
      Set<String> scopes,
      String state,
      Map<String, Object> additionalParameters,
      Map<String, Object> attributes) {}

  private final LoginRequests requests;
  private final JsonMapper json;

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    return Optional.ofNullable(WebUtils.getCookie(request, COOKIE))
        .flatMap(cookie -> requests.get(cookie.getValue()))
        .map(this::read)
        .orElse(null);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    // A browser that starts again leaves its earlier login behind: that key is dropped here.
    var previous = WebUtils.getCookie(request, COOKIE);
    if (previous != null) {
      requests.take(previous.getValue());
    }
    var id = IDS.generateKey();
    requests.put(
        id,
        json.writeValueAsString(
            new Stored(
                authorization.getAuthorizationUri(),
                authorization.getClientId(),
                authorization.getRedirectUri(),
                authorization.getScopes(),
                authorization.getState(),
                authorization.getAdditionalParameters(),
                authorization.getAttributes())));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(id, LoginRequests.LIFETIME));
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    var cookie = WebUtils.getCookie(request, COOKIE);
    if (cookie == null) {
      return null;
    }
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    return requests.take(cookie.getValue()).map(this::read).orElse(null);
  }

  private OAuth2AuthorizationRequest read(String stored) {
    var request = json.readValue(stored, Stored.class);
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri(request.authorizationUri())
        .clientId(request.clientId())
        .redirectUri(request.redirectUri())
        .scopes(request.scopes())
        .state(request.state())
        .additionalParameters(request.additionalParameters())
        .attributes(request.attributes())
        .build();
  }

  private static String cookie(String value, Duration maxAge) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
