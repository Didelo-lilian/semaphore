package fr.didelo.semaphore.ws;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class GameSocketHandler extends TextWebSocketHandler {

  private final JsonMapper json;

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    ServerMessage reply;
    try {
      reply =
          switch (json.readValue(message.getPayload(), ClientMessage.class)) {
            case ClientMessage.Echo echo -> new ServerMessage.Echoed(echo.text());
          };
    } catch (JacksonException e) {
      reply = new ServerMessage.Error("unreadable message");
    }
    session.sendMessage(new TextMessage(json.writeValueAsString(reply)));
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    session.close(CloseStatus.SERVER_ERROR);
  }
}
