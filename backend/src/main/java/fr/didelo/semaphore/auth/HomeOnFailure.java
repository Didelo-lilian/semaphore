package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.game.Unavailable;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A login is two navigations of the browser, so when Valkey or the database is out of reach the
 * player goes home with the code of every other failure (ADR 0011), not onto the JSON 503 of the
 * API ({@code BusyWhenSessionsAreOut}).
 */
@Slf4j
final class HomeOnFailure extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (ServletException | RuntimeException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if ((cause instanceof Unavailable || cause instanceof DataAccessException)
            && !response.isCommitted()) {
          log.warn("A sign-in found a store out of reach: {}", cause.toString());
          response.sendRedirect("/?login=failed");
          return;
        }
      }
      throw e;
    }
  }
}
