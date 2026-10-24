package fr.didelo.semaphore.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.WordList;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.databind.json.JsonMapper;

class GameSocketHandlerTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final RoomRegistry registry = spy(new RoomRegistry(Clock.systemUTC(), new WordList(), 2));
  private final GameSocketHandler handler = new GameSocketHandler(json, registry);

  private WebSocketSession connect(String id) throws Exception {
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    handler.afterConnectionEstablished(session);
    return session;
  }

  private void receive(WebSocketSession session, String message) {
    handler.handleTextMessage(session, new TextMessage(message));
  }

  private String lastPayload(WebSocketSession session) throws Exception {
    var sent = ArgumentCaptor.forClass(TextMessage.class);
    verify(session, atLeastOnce()).sendMessage(sent.capture());
    return sent.getValue().getPayload();
  }

  @Test
  void aConnectionThatBreaksDoesNotStopTheOthersFromGettingTheUpdate() throws Exception {
    var host = connect("host");
    var broken = connect("broken");
    receive(host, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");
    var code = json.readTree(lastPayload(host)).path("room").path("code").asString();
    doThrow(new SessionLimitExceededException("buffer full", CloseStatus.SESSION_NOT_RELIABLE))
        .when(broken)
        .sendMessage(any());
    receive(broken, "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"Bob\"}");

    receive(host, "{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");

    var update = json.readTree(lastPayload(host));
    assertThat(update.path("type").asString()).isEqualTo("roomUpdated");
    assertThat(update.path("room").path("players").path(0).path("team").asString())
        .isEqualTo("BABORD");
  }

  @Test
  void aRoomThatCouldNotBeEnteredIsNotKept() throws Exception {
    var session = connect("s1");

    receive(session, "{\"type\":\"createRoom\",\"name\":\"   \"}");

    assertThat(lastPayload(session))
        .contains("\"error\"")
        .contains("a name has 1 to 20 characters");
    verify(registry).drop(any());
  }

  @Test
  void aSecondRoomOnTheSameConnectionIsRefusedAndNotKept() throws Exception {
    var session = connect("s1");
    receive(session, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");

    receive(session, "{\"type\":\"createRoom\",\"name\":\"Ana\"}");

    assertThat(lastPayload(session)).contains("you are already in a room");
    verify(registry, times(1)).drop(any());
  }
}
