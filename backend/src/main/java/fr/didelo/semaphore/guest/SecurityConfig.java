package fr.didelo.semaphore.guest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security does one thing here: CSRF protection of the HTTP endpoints (ADR 0010). What
 * authorises a request is its session, so every path is open; there is no login page and no logout.
 * The token is a cookie the page reads and sends back in {@code X-XSRF-TOKEN}; its {@code __Host-}
 * prefix keeps a sibling subdomain from planting its own. Spring Security has no user here, so its
 * session management, security context and anonymous user are off: by default they read the session
 * on every request, and the WebSocket handshake would fail on a Valkey that cannot be reached,
 * where it can go on without a session.
 */
@Configuration
class SecurityConfig {

  /** The double submit of ADR 0010, shared with the chain of the sign-in (ADR 0011). */
  @Bean
  CsrfTokenRepository csrfTokens() {
    var tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    tokens.setCookieName("__Host-XSRF-TOKEN");
    tokens.setCookieCustomizer(cookie -> cookie.secure(true).path("/").sameSite("Lax"));
    return tokens;
  }

  @Bean
  SecurityFilterChain filters(HttpSecurity http, CsrfTokenRepository tokens) {
    return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .sessionManagement(AbstractHttpConfigurer::disable)
        .securityContext(AbstractHttpConfigurer::disable)
        .anonymous(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(tokens)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
        .build();
  }

  /** The token is created when asked for: ask on every request so a GET hands the cookie out. */
  private static final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
      chain.doFilter(request, response);
    }
  }
}
