package fr.didelo.semaphore.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.Postgres;
import fr.didelo.semaphore.game.EndReason;
import fr.didelo.semaphore.game.GameResult;
import fr.didelo.semaphore.game.GameResult.Participant;
import fr.didelo.semaphore.game.GameResult.Role;
import fr.didelo.semaphore.game.Team;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
    Postgres.connect(registry, Postgres.shared());
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
            new Participant(
                UUID.randomUUID().toString(), "ana", Team.BABORD, Role.SIGNALER, account),
            new Participant(UUID.randomUUID().toString(), "bob", Team.BABORD, Role.LOOKOUT, null),
            new Participant(
                UUID.randomUUID().toString(), "cleo", Team.TRIBORD, Role.SIGNALER, null),
            new Participant(
                UUID.randomUUID().toString(), "dan", Team.TRIBORD, Role.LOOKOUT, null)));
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
    var account = Postgres.account(jdbc);

    assertThat(history.record(result(id, account))).isTrue();

    var game =
        jdbc.sql("SELECT ended_at, winner, end_reason FROM game WHERE id = ?")
            .param(id)
            .query(
                (rs, row) ->
                    List.of(
                        rs.getObject("ended_at", OffsetDateTime.class).toInstant(),
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
    var late = Postgres.account(jdbc);
    assertThat(history.record(result(id, null))).isTrue();

    assertThat(history.record(result(id, late))).isFalse();

    assertThat(count("game", "id", id)).isOne();
    assertThat(count("game_player", "game_id", id)).isEqualTo(4);
    assertThat(count("game_player", "account_id", late)).isZero();
  }

  @Test
  void twoReplicasWritingTheSameGameAtOnceWriteItOnce() throws Exception {
    var id = UUID.randomUUID();
    var go = new CountDownLatch(1);
    var written = 0;
    try (var replicas = Executors.newFixedThreadPool(4)) {
      var writes =
          IntStream.range(0, 4)
              .mapToObj(
                  i ->
                      replicas.submit(
                          () -> {
                            go.await();
                            return history.record(result(id, Postgres.account(jdbc)));
                          }))
              .toList();
      go.countDown();
      for (var write : writes) {
        if (write.get(10, TimeUnit.SECONDS)) {
          written++;
        }
      }
    }

    assertThat(written).isOne();
    assertThat(count("game", "id", id)).isOne();
    assertThat(count("game_player", "game_id", id)).isEqualTo(4);
  }

  @Test
  void forgettingAnAccountKeepsTheTableAndDropsTheGamesNobodyIsLeftIn() {
    var gone = Postgres.account(jdbc);
    var stays = Postgres.account(jdbc);
    var shared = UUID.randomUUID();
    var alone = UUID.randomUUID();
    history.record(
        new GameResult(
            shared,
            Instant.parse("2026-10-25T12:00:00Z"),
            Team.BABORD,
            EndReason.COMPLETE,
            List.of(
                new Participant("p1", "ana", Team.BABORD, Role.SIGNALER, gone),
                new Participant("p2", "bob", Team.BABORD, Role.LOOKOUT, stays),
                new Participant("p3", "cleo", Team.TRIBORD, Role.SIGNALER, null))));
    history.record(result(alone, gone));

    history.forget(gone);

    assertThat(count("game", "id", alone)).isZero();
    assertThat(count("game_player", "game_id", alone)).isZero();
    assertThat(count("game", "id", shared)).isOne();
    assertThat(
            jdbc.sql(
                    "SELECT name || ' ' || coalesce(account_id::text, '-') FROM game_player WHERE game_id = ? ORDER BY player_id")
                .param(shared)
                .query(String.class)
                .list())
        .containsExactly("a former player -", "bob " + stays, "cleo -");
    assertThat(count("game_player", "account_id", gone)).isZero();
  }

  @Test
  void aSeatWhoseAccountWasDeletedMeanwhileIsWrittenAsAGuestAndTheGameIsKept() {
    var id = UUID.randomUUID();
    var deleted = UUID.randomUUID();

    assertThat(history.record(result(id, deleted))).isTrue();

    assertThat(count("game", "id", id)).isOne();
    assertThat(count("game_player", "game_id", id)).isEqualTo(4);
    assertThat(count("game_player", "account_id", deleted)).isZero();
  }

  @Test
  void twoPeopleOfOneGameForgottenAtOnceDropTheGame() throws Exception {
    try (var forgetters = Executors.newFixedThreadPool(2)) {
      for (var round = 0; round < 20; round++) {
        var first = Postgres.account(jdbc);
        var second = Postgres.account(jdbc);
        var id = UUID.randomUUID();
        history.record(
            new GameResult(
                id,
                Instant.parse("2026-10-25T12:00:00Z"),
                Team.BABORD,
                EndReason.COMPLETE,
                List.of(
                    new Participant("p1", "ana", Team.BABORD, Role.SIGNALER, first),
                    new Participant("p2", "bob", Team.TRIBORD, Role.SIGNALER, second))));
        var go = new CountDownLatch(1);
        var forgets =
            List.of(first, second).stream()
                .map(
                    account ->
                        forgetters.submit(
                            () -> {
                              go.await();
                              history.forget(account);
                              return null;
                            }))
                .toList();
        go.countDown();
        for (var forget : forgets) {
          forget.get(10, TimeUnit.SECONDS);
        }

        assertThat(count("game", "id", id)).as("round " + round).isZero();
      }
    }
  }

  @Test
  void forgettingAnAccountWithNoGameDoesNothing() {
    history.forget(Postgres.account(jdbc));
  }

  @Test
  void aGameWhosePlayersCannotAllBeWrittenLeavesNothing() {
    var id = UUID.randomUUID();
    var twice = UUID.randomUUID().toString();
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
