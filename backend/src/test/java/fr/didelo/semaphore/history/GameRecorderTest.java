package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.Moves;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

class GameRecorderTest {

  private final GameHistory history = mock(GameHistory.class);
  private final GameRecorder recorder = new GameRecorder(history);
  private final Logger logger = (Logger) LoggerFactory.getLogger(GameRecorder.class);
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
  void aGameWithAnAccountInItIsWritten() {
    recorder.ended(Moves.ended(UUID.randomUUID()));

    verify(history, timeout(2_000)).record(any(GameResult.class));
  }

  @Test
  void aGameOfGuestsOnlyIsNotWritten() {
    recorder.ended(Moves.ended(null));

    verify(history, after()).record(any());
  }

  @Test
  void aWriteThatFailsIsLoggedAndTheSameThreadWritesTheNextOne() {
    var threads = new CopyOnWriteArrayList<Thread>();
    when(history.record(any()))
        .thenAnswer(
            call -> {
              threads.add(Thread.currentThread());
              if (threads.size() == 1) {
                throw new DataAccessResourceFailureException("Postgres is down");
              }
              return true;
            });

    recorder.ended(Moves.ended(UUID.randomUUID()));
    recorder.ended(Moves.ended(UUID.randomUUID()));

    await().atMost(Duration.ofSeconds(2)).until(() -> threads.size() == 2);
    // A thread that died would have been replaced by another one.
    assertThat(threads.get(1)).isSameAs(threads.get(0));
    assertThat(List.copyOf(logs.list))
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(
            message -> message.contains("could not be recorded") && message.contains("Postgres"));
  }

  private static VerificationMode after() {
    return Mockito.after(300).never();
  }
}
