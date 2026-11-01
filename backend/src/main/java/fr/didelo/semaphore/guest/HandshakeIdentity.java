package fr.didelo.semaphore.guest;

import java.util.List;
import java.util.Map;
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

  private static final List<String> NAMES =
      List.of(Identity.ID, Identity.NAME, Identity.ACCOUNT_ID);

  private final SessionRepository<?> sessions;
  private final CookieSerializer cookies;

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
          for (var name : NAMES) {
            var value = session.getAttribute(name);
            if (value != null) {
              attributes.put(name, value);
            }
          }
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
