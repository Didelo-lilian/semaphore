package fr.didelo.semaphore.history;

import fr.didelo.semaphore.db.WithDatabase;
import fr.didelo.semaphore.game.GameResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The finished games, in Postgres. */
@Component
@WithDatabase
@RequiredArgsConstructor
public class GameHistory {

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

  /**
   * Removes the person and keeps the table (ADR 0012), all or nothing: the account's rows lose
   * their account id and their name, then the games left with no account in them go. The other
   * players' history does not change.
   */
  public void forget(UUID accountId) {
    transaction.executeWithoutResult(
        status -> {
          var games =
              jdbc.sql(
                      """
                      UPDATE game_player SET account_id = NULL, name = 'a former player'
                      WHERE account_id = ?
                      RETURNING game_id
                      """)
                  .param(accountId)
                  .query(UUID.class)
                  .list();
          if (!games.isEmpty()) {
            jdbc.sql(
                    """
                    DELETE FROM game
                    WHERE id IN (:games)
                      AND NOT EXISTS (
                        SELECT 1 FROM game_player WHERE game_id = game.id AND account_id IS NOT NULL)
                    """)
                .param("games", games)
                .update();
          }
        });
  }
}
