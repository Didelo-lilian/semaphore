package fr.didelo.semaphore.guest;

import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The sessions are in Valkey, and the request reads and writes them in several places (the
 * controller, the end of the response, Spring Security): a Valkey that cannot be reached would come
 * out as a 500 from any of them. This turns it into the 503 the rooms answer with, for the HTTP
 * API; the WebSocket handshake goes on without a session instead (ADR 0010).
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
class BusyWhenSessionsAreOut extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (ServletException | RuntimeException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if ((cause instanceof RedisException || cause instanceof DataAccessException)
            && !response.isCommitted()) {
          response.resetBuffer();
          response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          response
              .getOutputStream()
              .write(
                  "{\"error\":\"the server is busy, try again\"}".getBytes(StandardCharsets.UTF_8));
          return;
        }
      }
      throw e;
    }
  }
}
