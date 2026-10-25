package fr.didelo.semaphore.store;

import io.lettuce.core.RedisURI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Says where the rooms are kept. Two replicas started without {@code SEMAPHORE_REDIS_URL} would
 * each keep their own rooms in silence: the line is how to tell. The password is never in it.
 */
@Slf4j
@Component
class StoreLog {

  StoreLog(@Value("${" + RedisConfigured.URL + ":}") String url) {
    if (url.isBlank()) {
      log.info("rooms: memory");
    } else {
      var uri = RedisURI.create(url);
      log.info("rooms: redis {}:{}", uri.getHost(), uri.getPort());
    }
  }
}
