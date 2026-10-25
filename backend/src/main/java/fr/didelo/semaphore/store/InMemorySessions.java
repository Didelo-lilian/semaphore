package fr.didelo.semaphore.store;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSessionRepository;

/**
 * One process, sessions in its memory: development and tests. A session is dropped when read late.
 */
@Configuration
@WithoutRedis
class InMemorySessions {

  @Bean
  MapSessionRepository sessions(@Value("${semaphore.session.lifetime}") Duration lifetime) {
    var sessions = new MapSessionRepository(new ConcurrentHashMap<>());
    sessions.setDefaultMaxInactiveInterval(lifetime);
    return sessions;
  }
}
