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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
@Slf4j
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
class MeController {

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
    session.setAttribute(SessionKeys.GUEST_NAME, name);
    if (session.getAttribute(SessionKeys.GUEST_ID) == null) {
      session.setAttribute(SessionKeys.GUEST_ID, UUID.randomUUID().toString());
    }
    accounts.ifPresent(
        accounts -> accounts.signedIn(session).ifPresent(id -> accounts.rename(id, name)));
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
   * The account is read at each call: it may have been renamed or deleted from another browser, so
   * the session takes its name again, or goes back to being a guest's with the name it had. With
   * the database out of reach the session answers alone, since the game does not need it (ADR
   * 0013).
   */
  private Me me(HttpSession session) {
    var signedIn = accounts.flatMap(accounts -> accounts.signedIn(session));
    if (signedIn.isPresent()) {
      var id = signedIn.get().toString();
      try {
        var account = accounts.get().find(signedIn.get());
        if (account.isPresent()) {
          var name = account.get().displayName();
          if (!name.equals(session.getAttribute(SessionKeys.GUEST_NAME))) {
            session.setAttribute(SessionKeys.GUEST_NAME, name);
          }
          return new Me("account", id, name, null);
        }
        session.removeAttribute(SessionKeys.ACCOUNT_ID);
        session.removeAttribute(SessionKeys.ACCOUNT_AT);
      } catch (DataAccessException e) {
        log.warn("The account of a session could not be read: {}", e.toString());
        return new Me("account", id, (String) session.getAttribute(SessionKeys.GUEST_NAME), null);
      }
    }
    return new Me(
        "guest",
        (String) session.getAttribute(SessionKeys.GUEST_ID),
        (String) session.getAttribute(SessionKeys.GUEST_NAME),
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
