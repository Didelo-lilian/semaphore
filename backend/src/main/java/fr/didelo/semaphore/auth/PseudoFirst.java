package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.guest.SessionKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A login starts only for a browser that already chose a pseudo and has no account yet (ADR 0011):
 * the account is created with the pseudo, a session that signed in keeps its account, and a browser
 * with no session at all cannot start one (the logins in flight are held in Valkey, and the edge
 * limits how many one address starts).
 */
@RequiredArgsConstructor
final class PseudoFirst extends OncePerRequestFilter {

  private final Accounts accounts;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(Providers.LOGIN);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var session = request.getSession(false);
    if (session == null || session.getAttribute(SessionKeys.GUEST_NAME) == null) {
      response.sendRedirect(
          "/?login=pseudo&provider="
              + request.getRequestURI().substring(Providers.LOGIN.length() + 1));
    } else if (accounts.signedIn(session).isPresent()) {
      response.sendRedirect("/");
    } else {
      chain.doFilter(request, response);
    }
  }
}
