package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.guest.SessionKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A login starts only for a browser that already chose a pseudo (ADR 0011): the account is created
 * with it, and a visitor with no session cannot fill the store of logins in flight.
 */
final class PseudoFirst extends OncePerRequestFilter {

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
      response.sendRedirect("/?login=pseudo");
      return;
    }
    chain.doFilter(request, response);
  }
}
