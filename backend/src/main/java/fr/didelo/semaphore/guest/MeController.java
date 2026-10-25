package fr.didelo.semaphore.guest;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.PlayerName;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the browser is, outside any room (ADR 0010). Only {@code PUT} creates a session: a visitor
 * who never chose a pseudo costs nothing.
 */
@RestController
@RequestMapping("/api/me")
class MeController {

  static final String ID = "guest.id";
  static final String NAME = "guest.name";

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Me(String kind, String id, String name) {}

  record Rename(String name) {
    Rename {
      requireNonNull(name);
    }
  }

  record Problem(String error) {}

  @GetMapping
  Me get(HttpServletRequest request) {
    var session = request.getSession(false);
    return session == null || session.getAttribute(ID) == null
        ? new Me("anonymous", null, null)
        : guest(session);
  }

  @PutMapping
  Me put(@RequestBody Rename rename, HttpServletRequest request) {
    var name = PlayerName.of(rename.name());
    var session = request.getSession();
    session.setAttribute(NAME, name);
    if (session.getAttribute(ID) == null) {
      session.setAttribute(ID, UUID.randomUUID().toString());
    }
    return guest(session);
  }

  @DeleteMapping
  ResponseEntity<Void> delete(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  private static Me guest(HttpSession session) {
    return new Me("guest", (String) session.getAttribute(ID), (String) session.getAttribute(NAME));
  }

  @ExceptionHandler(IllegalMove.class)
  ResponseEntity<Problem> refused(IllegalMove e) {
    return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
  }
}
