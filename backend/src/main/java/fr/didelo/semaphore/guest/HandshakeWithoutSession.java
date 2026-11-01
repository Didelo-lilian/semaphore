package fr.didelo.semaphore.guest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The session filter reads the session when anything asks the request for it, Tomcat does at every
 * upgrade, and saves it back when the answer is committed: with Valkey out of reach the read
 * refuses the handshake, and with Valkey full the write hangs it. For {@code /ws} the request has
 * no session, so the filter has nothing to read or save; {@link HandshakeIdentity} reads it without
 * writing. The HTTP API answers 503 instead ({@link BusyWhenSessionsAreOut}).
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 1)
class HandshakeWithoutSession extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().equals("/ws");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public HttpSession getSession(boolean create) {
            return null;
          }

          @Override
          public HttpSession getSession() {
            return null;
          }
        },
        response);
  }
}
