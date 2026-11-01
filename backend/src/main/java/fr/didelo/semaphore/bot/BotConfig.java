package fr.didelo.semaphore.bot;

import java.io.IOException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@Configuration
class BotConfig {

  /** Read at start, so a corrupt file stops the replica instead of the first bot. */
  @Bean
  WordVectors wordVectors() throws IOException {
    var start = System.nanoTime();
    try (var in = new ClassPathResource("bot/fr-vectors.bin").getInputStream()) {
      var vectors = WordVectors.load(in);
      log.info(
          "bot vectors: {} words, {} dimensions, {} ms",
          vectors.size(),
          vectors.dimensions(),
          Duration.ofNanos(System.nanoTime() - start).toMillis());
      return vectors;
    }
  }
}
