package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.Moves;
import fr.didelo.semaphore.game.RoomState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class GameRecorderTest {

  private final GameHistory history = mock(GameHistory.class);
  private final GameRecorder recorder = new GameRecorder(history);

  private static RoomState withoutGameId(RoomState state) {
    return new RoomState(
        state.code(),
        state.version(),
        state.phase(),
        state.turn(),
        state.signal(),
        state.revealsLeft(),
        state.winner(),
        state.flags(),
        state.board(),
        state.players(),
        state.spectators(),
        state.lastActivity(),
        state.lastMove(),
        null,
        state.endedAt());
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
  void aGameFromBeforeTheHistoryHadAnIdIsNotWritten() {
    recorder.ended(withoutGameId(Moves.ended(UUID.randomUUID())));

    verify(history, after()).record(any());
  }

  @Test
  void aWriteThatFailsIsGivenUpAndTheNextOneIsWritten() {
    doThrow(new DataAccessResourceFailureException("Postgres is down"))
        .doReturn(true)
        .when(history)
        .record(any());

    assertThatCode(
            () -> {
              recorder.ended(Moves.ended(UUID.randomUUID()));
              recorder.ended(Moves.ended(UUID.randomUUID()));
            })
        .doesNotThrowAnyException();

    verify(history, timeout(2_000).times(2)).record(any());
  }

  private static org.mockito.verification.VerificationMode after() {
    return org.mockito.Mockito.after(300).never();
  }
}
