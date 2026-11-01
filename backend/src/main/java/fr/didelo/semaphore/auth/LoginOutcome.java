package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.guest.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * What a login ends in (ADR 0011). On success the account is found or created from the provider's
 * id alone and the session of ADR 0010 gets its id and its name, under a new session id; Spring's
 * {@code Authentication} and the provider's token die with the request. On failure the browser is
 * sent home with a fixed code: nothing the provider said is echoed.
 */
@Slf4j
@Component
@WithSignIn
@RequiredArgsConstructor
class LoginOutcome implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  private final Accounts accounts;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var session = request.getSession(false);
    var pseudo = session == null ? null : (String) session.getAttribute(SessionKeys.GUEST_NAME);
    if (pseudo == null) {
      // The session ended while the player was at the provider's.
      response.sendRedirect("/?login=pseudo");
      return;
    }
    var login = (OAuth2AuthenticationToken) authentication;
    Accounts.Account account;
    try {
      account =
          accounts.findOrCreate(login.getAuthorizedClientRegistrationId(), login.getName(), pseudo);
    } catch (DataAccessException e) {
      log.warn("A sign-in could not reach the database: {}", e.toString());
      response.sendRedirect("/?login=failed");
      return;
    }
    // Against session fixation: whatever id the browser held before, it holds another now.
    request.changeSessionId();
    session.setAttribute(SessionKeys.ACCOUNT_ID, account.id().toString());
    session.setAttribute(SessionKeys.GUEST_NAME, account.displayName());
    response.sendRedirect("/");
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    log.info("A sign-in failed: {}", exception.getMessage());
    response.sendRedirect("/?login=failed");
  }
}
