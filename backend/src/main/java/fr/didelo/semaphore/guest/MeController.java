package fr.didelo.semaphore.guest;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.didelo.semaphore.auth.Accounts;
import fr.didelo.semaphore.auth.Providers;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.PlayerName;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the browser is, outside any room (ADR 0010): nobody, a guest, or the account it signed in to
 * (ADR 0011). Only {@code PUT} creates a session: a visitor who never chose a pseudo costs nothing.
 * Sign-in is optional, so its beans may be missing: then no provider is on, and a session that
 * names an account no longer has one.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
class MeController {

  static final String ID = SessionKeys.GUEST_ID;
  static final String NAME = SessionKeys.GUEST_NAME;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Me(String kind, String id, String name, List<String> providers) {}

  record Rename(String name) {
    Rename {
      requireNonNull(name);
    }
  }

  record Problem(String error) {}

  private final Optional<Providers> providers;
  private final Optional<Accounts> accounts;

  @GetMapping
  Me get(HttpServletRequest request) {
    var session = request.getSession(false);
    return session == null ? new Me("anonymous", null, null, providers()) : me(session);
  }

  @PutMapping
  Me put(@RequestBody Rename rename, HttpServletRequest request) {
    var name = PlayerName.of(rename.name());
    var session = request.getSession();
    session.setAttribute(NAME, name);
    if (session.getAttribute(ID) == null) {
      session.setAttribute(ID, UUID.randomUUID().toString());
    }
    var accountId = (String) session.getAttribute(SessionKeys.ACCOUNT_ID);
    if (accountId != null) {
      accounts.ifPresent(accounts -> accounts.rename(UUID.fromString(accountId), name));
    }
    return me(session);
  }

  @DeleteMapping
  ResponseEntity<Void> delete(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * The account is read at each call: it may have been deleted from another browser, and the
   * session then goes back to being a guest's, with the name it had.
   */
  private Me me(HttpSession session) {
    var accountId = (String) session.getAttribute(SessionKeys.ACCOUNT_ID);
    if (accountId != null) {
      var account = accounts.flatMap(accounts -> accounts.find(UUID.fromString(accountId)));
      if (account.isPresent()) {
        return new Me("account", accountId, account.get().displayName(), null);
      }
      session.removeAttribute(SessionKeys.ACCOUNT_ID);
    }
    return new Me(
        "guest",
        (String) session.getAttribute(ID),
        (String) session.getAttribute(NAME),
        providers());
  }

  private List<String> providers() {
    return providers.map(Providers::names).orElse(List.of());
  }

  @ExceptionHandler(IllegalMove.class)
  ResponseEntity<Problem> refused(IllegalMove e) {
    return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Problem> unreadable() {
    return ResponseEntity.badRequest()
        .body(new Problem("a body like {\"name\":\"ana\"} is expected"));
  }
}
