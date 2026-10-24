package fr.didelo.semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIT {

  private static final String ALLOWED_ORIGIN = "https://play.didelo.fr";

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();

  private WebSocket connect(String origin) {
    return http.newWebSocketBuilder()
        .header("Origin", origin)
        .buildAsync(
            URI.create("ws://localhost:" + port + "/ws"),
            new WebSocket.Listener() {
              @Override
              public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                received.add(data.toString());
                ws.request(1);
                return null;
              }
            })
        .join();
  }

  @Test
  void healthIsUp() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void echoesATypedMessage() throws Exception {
    var ws = connect(ALLOWED_ORIGIN);

    ws.sendText("{\"type\":\"echo\",\"text\":\"ahoy\"}", true).join();

    assertThat(received.poll(5, TimeUnit.SECONDS))
        .isEqualTo("{\"type\":\"echoed\",\"text\":\"ahoy\"}");
  }

  @Test
  void answersAnErrorToAnUnreadableMessageAndStaysOpen() throws Exception {
    var ws = connect(ALLOWED_ORIGIN);

    ws.sendText("{\"type\":\"nope\"}", true).join();
    assertThat(received.poll(5, TimeUnit.SECONDS))
        .isEqualTo("{\"type\":\"error\",\"reason\":\"unreadable message\"}");

    ws.sendText("{\"type\":\"echo\",\"text\":\"still here\"}", true).join();
    assertThat(received.poll(5, TimeUnit.SECONDS))
        .isEqualTo("{\"type\":\"echoed\",\"text\":\"still here\"}");
  }

  @Test
  void refusesAnotherOrigin() {
    assertThatThrownBy(() -> connect("https://evil.example"))
        .hasCauseInstanceOf(WebSocketHandshakeException.class);
  }
}
