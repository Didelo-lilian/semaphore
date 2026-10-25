package fr.didelo.semaphore.history;

import fr.didelo.semaphore.db.WithDatabase;
import fr.didelo.semaphore.game.GameResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The finished games, in Postgres. */
@Component
@WithDatabase
@RequiredArgsConstructor
class GameHistory {

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;

  /**
   * Writes a game and its players, all or nothing. A game that is already there is left as it is:
   * it returns false, which is what a second replica or a retry meets.
   */
  boolean record(GameResult result) {
    return transaction.execute(
        status -> {
          var written =
              jdbc.sql(
                      """
                      INSERT INTO game (id, ended_at, winner, end_reason)
                      VALUES (?, ?, ?, ?)
                      ON CONFLICT (id) DO NOTHING
                      """)
                  .params(
                      result.id(),
                      OffsetDateTime.ofInstant(result.endedAt(), ZoneOffset.UTC),
                      result.winner().name(),
                      result.endReason().name())
                  .update();
          if (written == 0) {
            return false;
          }
          for (var player : result.participants()) {
            jdbc.sql(
                    """
                    INSERT INTO game_player (game_id, player_id, name, team, role, account_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(
                    result.id(),
                    player.playerId(),
                    player.name(),
                    player.team().name(),
                    player.role().name(),
                    player.accountId())
                .update();
          }
          return true;
        });
  }
}
