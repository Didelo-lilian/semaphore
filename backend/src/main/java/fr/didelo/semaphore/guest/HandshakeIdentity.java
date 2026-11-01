package fr.didelo.semaphore.guest;

import fr.didelo.semaphore.auth.Accounts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Copies who the session of the cookie says the browser is into the socket's attributes. It reads
 * the session from the repository and never asks the request for it: the session filter would save
 * it back when the answer is committed, and with Valkey full that write leaves the upgrade hanging.
 * A session that cannot be read is none: the handshake goes on, and the message names the player
 * (ADR 0010).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandshakeIdentity implements HandshakeInterceptor {

  private final SessionRepository<?> sessions;
  private final CookieSerializer cookies;
  private final Optional<Accounts> accounts;

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    var ids = cookies.readCookieValues(((ServletServerHttpRequest) request).getServletRequest());
    if (!ids.isEmpty()) {
      try {
        var session = sessions.findById(ids.getFirst());
        if (session != null) {
          for (var name : List.of(SessionKeys.GUEST_ID, SessionKeys.GUEST_NAME)) {
            var value = session.getAttribute(name);
            if (value != null) {
              attributes.put(name, value);
            }
          }
          // A login too old for the cookie is no login; the account is not read in the database,
          // which the game does not need (ADR 0013): the history writes a deleted one as a guest.
          accounts
              .flatMap(
                  accounts ->
                      accounts.signedIn(
                          session.getAttribute(SessionKeys.ACCOUNT_ID),
                          session.getAttribute(SessionKeys.ACCOUNT_AT)))
              .ifPresent(account -> attributes.put(SessionKeys.ACCOUNT_ID, account));
        }
      } catch (RuntimeException e) {
        log.debug("The session of a handshake cannot be read: {}", e.toString());
      }
    }
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {}
}
