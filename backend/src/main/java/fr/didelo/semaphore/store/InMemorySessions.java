package fr.didelo.semaphore.store;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;

/**
 * One process, sessions in its memory: development and tests. A session is dropped when read late,
 * and the oldest go when there are too many, since one nobody reads again would never leave.
 */
@Configuration
@WithoutRedis
class InMemorySessions {

  private static final int MAX_SESSIONS = 10_000;

  @Bean
  MapSessionRepository sessions(@Value("${semaphore.session.idle-timeout}") Duration idleTimeout) {
    var sessions = new MapSessionRepository(Bounded.<String, Session>map(MAX_SESSIONS));
    sessions.setDefaultMaxInactiveInterval(idleTimeout);
    return sessions;
  }
}
