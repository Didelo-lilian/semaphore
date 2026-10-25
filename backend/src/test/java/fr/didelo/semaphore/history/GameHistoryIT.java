package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.GameResult.EndReason;
import fr.didelo.semaphore.game.GameResult.Participant;
import fr.didelo.semaphore.game.GameResult.Role;
import fr.didelo.semaphore.game.Team;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class GameHistoryIT {

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    var postgres = Postgres.shared();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired GameHistory history;
  @Autowired JdbcClient jdbc;

  private static GameResult result(UUID id, UUID account) {
    return new GameResult(
        id,
        Instant.parse("2026-10-25T12:00:00.123456Z"),
        Team.BABORD,
        EndReason.REEF,
        List.of(
            new Participant(UUID.randomUUID(), "ana", Team.BABORD, Role.SIGNALER, account),
            new Participant(UUID.randomUUID(), "bob", Team.BABORD, Role.LOOKOUT, null),
            new Participant(UUID.randomUUID(), "cleo", Team.TRIBORD, Role.SIGNALER, null),
            new Participant(UUID.randomUUID(), "dan", Team.TRIBORD, Role.LOOKOUT, null)));
  }

  private long count(String table, String column, UUID id) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  @Test
  void aGameIsWrittenWithItsPlayersAndWhoTheyAre() {
    var id = UUID.randomUUID();
    var account = UUID.randomUUID();

    assertThat(history.record(result(id, account))).isTrue();

    var game =
        jdbc.sql("SELECT ended_at, winner, end_reason FROM game WHERE id = ?")
            .param(id)
            .query(
                (rs, row) ->
                    List.of(
                        rs.getObject("ended_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("winner"),
                        rs.getString("end_reason")))
            .single();
    assertThat(game)
        .containsExactly(Instant.parse("2026-10-25T12:00:00.123456Z"), "BABORD", "REEF");
    var players =
        jdbc.sql(
                "SELECT name, team, role, account_id FROM game_player WHERE game_id = ? ORDER BY name")
            .param(id)
            .query(
                (rs, row) ->
                    rs.getString("name")
                        + " "
                        + rs.getString("team")
                        + " "
                        + rs.getString("role")
                        + " "
                        + rs.getObject("account_id", UUID.class))
            .list();
    assertThat(players)
        .containsExactly(
            "ana BABORD SIGNALER " + account,
            "bob BABORD LOOKOUT null",
            "cleo TRIBORD SIGNALER null",
            "dan TRIBORD LOOKOUT null");
  }

  @Test
  void aGameThatIsAlreadyThereIsLeftAsItIs() {
    var id = UUID.randomUUID();
    assertThat(history.record(result(id, null))).isTrue();

    assertThat(history.record(result(id, UUID.randomUUID()))).isFalse();

    assertThat(count("game", "id", id)).isOne();
    assertThat(count("game_player", "game_id", id)).isEqualTo(4);
    assertThat(count("game_player", "account_id", UUID.randomUUID())).isZero();
  }

  @Test
  void aGameWhosePlayersCannotAllBeWrittenLeavesNothing() {
    var id = UUID.randomUUID();
    var twice = UUID.randomUUID();
    var broken =
        new GameResult(
            id,
            Instant.parse("2026-10-25T12:00:00Z"),
            Team.TRIBORD,
            EndReason.COMPLETE,
            List.of(
                new Participant(twice, "ana", Team.BABORD, Role.SIGNALER, null),
                new Participant(twice, "bob", Team.TRIBORD, Role.SIGNALER, null)));

    assertThatThrownBy(() -> history.record(broken)).isInstanceOf(DuplicateKeyException.class);

    assertThat(count("game", "id", id)).isZero();
    assertThat(count("game_player", "game_id", id)).isZero();
  }
}
