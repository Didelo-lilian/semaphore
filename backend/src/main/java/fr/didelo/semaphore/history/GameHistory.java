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
   * it returns false, which is what a second replica or a retry meets. A seat whose account was
   * deleted since the player joined is written as a guest's, since the foreign key would otherwise
   * refuse the whole game.
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
                    VALUES (?, ?, ?, ?, ?, (SELECT id FROM account WHERE id = ?))
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
   * players' history does not change. The games are locked first, in one order: two people of one
   * game forgotten at once each see the other's seat as it was, and neither would drop the game.
   */
  public void forget(UUID accountId) {
    transaction.executeWithoutResult(
        status -> {
          var games =
              jdbc.sql(
                      """
                      SELECT id FROM game
                      WHERE id IN (SELECT game_id FROM game_player WHERE account_id = ?)
                      ORDER BY id
                      FOR UPDATE
                      """)
                  .param(accountId)
                  .query(UUID.class)
                  .list();
          if (games.isEmpty()) {
            return;
          }
          jdbc.sql(
                  """
                  UPDATE game_player SET account_id = NULL, name = 'a former player'
                  WHERE account_id = ?
                  """)
              .param(accountId)
              .update();
          jdbc.sql(
                  """
                  DELETE FROM game
                  WHERE id = ANY (?)
                    AND NOT EXISTS (
                      SELECT 1 FROM game_player WHERE game_id = game.id AND account_id IS NOT NULL)
                  """)
              .param(games.toArray(UUID[]::new))
              .update();
        });
  }
}
