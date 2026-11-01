package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.guest.SessionKeys;
import fr.didelo.semaphore.history.GameHistory;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The accounts of ADR 0011: a provider, the id it gives, and the name shown in the game. */
@RequiredArgsConstructor
public class Accounts {

  public record Account(UUID id, String displayName) {}

  private static final RowMapper<Account> ROW =
      (rows, i) -> new Account(rows.getObject("id", UUID.class), rows.getString("display_name"));

  private final JdbcClient jdbc;
  private final GameHistory history;
  private final TransactionTemplate transaction;
  private final Duration sessionLife;

  /**
   * The account this session signed in to, unless that was longer ago than the cookie lives: each
   * use renews a session in Valkey, so without this a copied cookie would hold the account for
   * good. A login that is too old is dropped from the session, which is then a guest's.
   */
  public Optional<UUID> signedIn(HttpSession session) {
    var id = (String) session.getAttribute(SessionKeys.ACCOUNT_ID);
    if (id == null) {
      return Optional.empty();
    }
    var at =
        Instant.ofEpochSecond(
            Long.parseLong((String) session.getAttribute(SessionKeys.ACCOUNT_AT)));
    if (at.plus(sessionLife).isAfter(Instant.now())) {
      return Optional.of(UUID.fromString(id));
    }
    session.removeAttribute(SessionKeys.ACCOUNT_ID);
    session.removeAttribute(SessionKeys.ACCOUNT_AT);
    return Optional.empty();
  }

  /**
   * The account of this identity, created with {@code displayName} the first time. One statement,
   * so two sign-ins at once of a new identity make one account.
   */
  Account findOrCreate(String provider, String subject, String displayName) {
    return jdbc.sql(
            """
            INSERT INTO account (id, provider, subject, display_name)
            VALUES (:id, :provider, :subject, :name)
            ON CONFLICT (provider, subject) DO UPDATE SET provider = account.provider
            RETURNING id, display_name
            """)
        .param("id", UUID.randomUUID())
        .param("provider", provider)
        .param("subject", subject)
        .param("name", displayName)
        .query(ROW)
        .single();
  }

  public Optional<Account> find(UUID id) {
    return jdbc.sql("SELECT id, display_name FROM account WHERE id = :id")
        .param("id", id)
        .query(ROW)
        .optional();
  }

  public void rename(UUID id, String displayName) {
    jdbc.sql("UPDATE account SET display_name = :name WHERE id = :id")
        .param("name", displayName)
        .param("id", id)
        .update();
  }

  /** The games are forgotten first, in the same transaction: the account is referenced by them. */
  public void delete(UUID id) {
    transaction.executeWithoutResult(
        status -> {
          history.forget(id);
          jdbc.sql("DELETE FROM account WHERE id = :id").param("id", id).update();
        });
  }
}
