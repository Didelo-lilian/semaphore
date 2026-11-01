package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.guest.Identity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

  private final GameSocketHandler handler;

  @Value("${semaphore.allowed-origins}")
  private String[] allowedOrigins;

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // Who the session says the browser is, if it has one: the handshake never fails on it.
    var identity =
        new HttpSessionHandshakeInterceptor(
            List.of(Identity.ID, Identity.NAME, Identity.ACCOUNT_ID));
    identity.setCopyHttpSessionId(false);
    registry.addHandler(handler, "/ws").setAllowedOrigins(allowedOrigins).addInterceptors(identity);
  }
}
