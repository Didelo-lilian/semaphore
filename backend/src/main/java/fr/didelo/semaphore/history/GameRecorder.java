package fr.didelo.semaphore.history;

import fr.didelo.semaphore.db.WithDatabase;
import fr.didelo.semaphore.game.GameEndListener;
import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.RoomState;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes the games that have ended, on a thread of its own so that nothing waits for Postgres. A
 * write that fails, or a queue that is full, is logged and given up: the sweep that drops an
 * abandoned room tries once more, and a game is never refused or undone for a database (ADR 0012).
 */
@Slf4j
@Component
@WithDatabase
class GameRecorder implements GameEndListener {

  private final GameHistory history;
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(100),
          task -> {
            var thread = new Thread(task, "game-recorder");
            thread.setDaemon(true);
            return thread;
          },
          (task, pool) -> log.warn("a game result was dropped, the queue of the recorder is full"));

  GameRecorder(GameHistory history) {
    this.history = history;
  }

  @Override
  public void ended(RoomState state) {
    // A game started by the version before the history has no id.
    if (state.gameId() == null) {
      return;
    }
    var result = GameResult.of(state);
    if (result.hasAccount()) {
      executor.execute(() -> write(result));
    }
  }

  void write(GameResult result) {
    try {
      if (history.record(result)) {
        log.info("game {} recorded", result.id());
      }
    } catch (RuntimeException e) {
      log.warn("game {} could not be recorded: {}", result.id(), e.toString());
    }
  }

  @PreDestroy
  void close() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(2, TimeUnit.SECONDS);
  }
}
