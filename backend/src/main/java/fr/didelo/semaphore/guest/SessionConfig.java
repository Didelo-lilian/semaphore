package fr.didelo.semaphore.guest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * The session cookie of ADR 0010. The repository is in memory or in Valkey (see the store package);
 * the cookie is the same: {@code __Host-} makes the browser insist on {@code Secure}, {@code
 * Path=/} and no {@code Domain}, and {@code Lax} is what lets it come back from GitHub and Google.
 */
@Configuration
@EnableSpringHttpSession
class SessionConfig {

  @Bean
  DefaultCookieSerializer cookieSerializer(
      @Value("${semaphore.session.lifetime}") Duration lifetime) {
    var cookie = new DefaultCookieSerializer();
    cookie.setCookieName("__Host-session");
    cookie.setCookiePath("/");
    cookie.setUseHttpOnlyCookie(true);
    cookie.setUseSecureCookie(true);
    cookie.setSameSite("Lax");
    cookie.setCookieMaxAge((int) lifetime.toSeconds());
    return cookie;
  }

  /**
   * A REST backend keeps no flash attributes, and the default manager reads the session on every
   * request: with Valkey out of reach that would fail a WebSocket handshake that can go on without
   * one.
   */
  @Bean(DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
  FlashMapManager flashMapManager() {
    return new FlashMapManager() {
      @Override
      public FlashMap retrieveAndUpdate(HttpServletRequest request, HttpServletResponse response) {
        return null;
      }

      @Override
      public void saveOutputFlashMap(
          FlashMap flashMap, HttpServletRequest request, HttpServletResponse response) {}
    };
  }
}
