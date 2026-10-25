package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.game.Moves;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Team;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole path, from a game that ends in the registry to its rows in Postgres. */
@SpringBootTest
class GameRecordingIT {

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    var postgres = Postgres.shared();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired RoomRegistry registry;
  @Autowired GameRecorder recorder;
  @Autowired JdbcClient jdbc;

  private long games(UUID id) {
    return jdbc.sql("SELECT count(*) FROM game WHERE id = ?").param(id).query(Long.class).single();
  }

  @Test
  void aGameThatEndsWithAnAccountInItIsWrittenOnce() {
    var account = UUID.randomUUID();
    var code = Moves.startedGame(registry, account);

    registry.change(code, room -> Moves.win(room, Team.TRIBORD));

    var state = registry.find(code).orElseThrow().state();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(state.gameId())).isOne());
    recorder.ended(state);
    recorder.ended(state);
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(state.gameId())).isOne());
    var players =
        jdbc.sql("SELECT name FROM game_player WHERE game_id = ? ORDER BY name")
            .param(state.gameId())
            .query(String.class)
            .list();
    assertThat(players).containsExactly("p0", "p1", "p2", "p3");
    assertThat(
            jdbc.sql("SELECT winner || ' ' || end_reason FROM game WHERE id = ?")
                .param(state.gameId())
                .query(String.class)
                .single())
        .isEqualTo("TRIBORD COMPLETE");
  }

  @Test
  void aGameOfGuestsOnlyIsNotWritten() {
    var code = Moves.startedGame(registry, null);

    registry.change(code, room -> Moves.win(room, Team.BABORD));

    var id = registry.find(code).orElseThrow().state().gameId();
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(games(id)).isZero());
  }
}
