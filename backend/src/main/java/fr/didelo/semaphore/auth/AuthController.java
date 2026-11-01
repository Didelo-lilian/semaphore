package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.guest.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ending a sign-in (ADR 0011), behind the CSRF token of ADR 0010. Starting one is Spring
 * Security's: {@link SignInConfig}.
 */
@RestController
@WithSignIn
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

  private final Accounts accounts;

  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Deletes the account and ends the session that asked. The other browsers signed in to it learn
   * at their next {@code /api/me}: sessions cannot be listed by account (ADR 0010).
   */
  @DeleteMapping("/account")
  ResponseEntity<Void> delete(HttpServletRequest request) {
    var session = request.getSession(false);
    var id = session == null ? null : (String) session.getAttribute(SessionKeys.ACCOUNT_ID);
    if (id == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    accounts.delete(UUID.fromString(id));
    session.invalidate();
    return ResponseEntity.noContent().build();
  }
}
