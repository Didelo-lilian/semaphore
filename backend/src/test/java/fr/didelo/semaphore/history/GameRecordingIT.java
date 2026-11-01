package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.Moves;
import fr.didelo.semaphore.game.MutableClock;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Team;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The whole path, from a game that ends in the registry to its rows in Postgres. */
@SpringBootTest
class GameRecordingIT {

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    MutableClock movableClock() {
      return new MutableClock();
    }
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    Postgres.connect(registry, Postgres.shared());
  }

  @Autowired RoomRegistry registry;
  @Autowired MutableClock clock;
  @Autowired JdbcClient jdbc;
  @MockitoSpyBean GameHistory history;
  @Autowired GameRecorder recorder;

  private UUID account() {
    var id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO account (id, provider, subject, display_name) VALUES (?, 'github', ?, 'ana')")
        .params(id, id.toString())
        .update();
    return id;
  }

  private long games(UUID id) {
    return jdbc.sql("SELECT count(*) FROM game WHERE id = ?").param(id).query(Long.class).single();
  }

  @Test
  void aGameThatEndsWithAnAccountInItIsWrittenOnce() {
    var code = Moves.startedGame(registry, account());

    registry.change(code, room -> Moves.win(room, Team.TRIBORD));

    var result = registry.find(code).orElseThrow().result().orElseThrow();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(result.id())).isOne());
    recorder.ended(result);
    recorder.ended(result);
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(result.id())).isOne());
    var players =
        jdbc.sql("SELECT name FROM game_player WHERE game_id = ? ORDER BY name")
            .param(result.id())
            .query(String.class)
            .list();
    assertThat(players).containsExactly("p0", "p1", "p2", "p3");
    assertThat(
            jdbc.sql("SELECT winner || ' ' || end_reason FROM game WHERE id = ?")
                .param(result.id())
                .query(String.class)
                .single())
        .isEqualTo("TRIBORD COMPLETE");
  }

  @Test
  void aGameOfGuestsOnlyIsNotWritten() {
    var code = Moves.startedGame(registry, null);

    registry.change(code, room -> Moves.win(room, Team.BABORD));

    var id = registry.find(code).orElseThrow().result().orElseThrow().id();
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(id)).isZero());
  }

  @Test
  void aGameWhoseFirstWriteFailsIsWrittenWhenItsAbandonedRoomIsSwept() {
    doThrow(new DataAccessResourceFailureException("Postgres is down"))
        .doCallRealMethod()
        .when(history)
        .record(any(GameResult.class));
    var code = Moves.startedGame(registry, account());

    registry.change(code, room -> Moves.win(room, Team.BABORD));

    var id = registry.find(code).orElseThrow().result().orElseThrow().id();
    verify(history, timeout(2_000)).record(any(GameResult.class));
    assertThat(games(id)).isZero();

    registry.change(
        code, room -> room.state().players().forEach(p -> room.disconnect(p.id(), Moves.REPLICA)));
    clock.advance(Duration.ofMinutes(31));
    registry.dropAbandoned();

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(games(id)).isOne());
    assertThat(registry.find(code)).isEmpty();
  }
}
