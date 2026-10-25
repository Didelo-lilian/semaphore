package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RoomFailuresTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(RoomFailures.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level level;

  @BeforeEach
  void listen() {
    level = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stopListening() {
    logger.detachAppender(logs);
    logger.setLevel(level);
  }

  @Test
  void aFailureIsLoggedWithItsStackTheFirstTimeAndQuietlyWhileItGoesOn() {
    var code = UUID.randomUUID().toString();

    RoomFailures.log(code, "swept", new IllegalStateException("broken"));
    RoomFailures.log(code, "swept", new IllegalStateException("broken"));
    RoomFailures.log(code, "swept", new IllegalStateException("broken another way"));

    assertThat(logs.list)
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN, Level.DEBUG, Level.WARN);
    assertThat(logs.list.get(0).getThrowableProxy()).isNotNull();
    assertThat(logs.list.get(1).getThrowableProxy()).isNull();
  }

  @Test
  void eachRoomHasItsOwnFirstTime() {
    RoomFailures.log(UUID.randomUUID().toString(), "swept", new IllegalStateException("broken"));
    RoomFailures.log(UUID.randomUUID().toString(), "swept", new IllegalStateException("broken"));

    assertThat(logs.list)
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN, Level.WARN);
  }
}
