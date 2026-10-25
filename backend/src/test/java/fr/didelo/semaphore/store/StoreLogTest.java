package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StoreLogTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(StoreLog.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void listen() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stopListening() {
    logger.detachAppender(logs);
  }

  @Test
  void itSaysTheRoomsAreInMemoryWhenThereIsNoUrl() {
    new StoreLog("");
    new StoreLog("  ");

    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("rooms: memory", "rooms: memory");
  }

  @Test
  void itSaysWhichRedisHoldsTheRoomsAndNeverItsPassword() {
    new StoreLog("redis://:s3cret@redis.internal:6380");

    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel().toString()).isEqualTo("INFO");
              assertThat(event.getFormattedMessage()).isEqualTo("rooms: redis redis.internal:6380");
            });
  }
}
