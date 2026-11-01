package fr.didelo.semaphore.guest;

import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A WebSocket handshake can go on without a session, but Tomcat reads the session at every upgrade:
 * with Valkey out of reach that read would refuse the handshake. For {@code /ws} only, a session
 * that cannot be read is none (ADR 0010); the HTTP API answers 503 instead ({@link
 * BusyWhenSessionsAreOut}).
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
            try {
              return super.getSession(create);
            } catch (RedisException | DataAccessException e) {
              return null;
            }
          }
        },
        response);
  }
}
