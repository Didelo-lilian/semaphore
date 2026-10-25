package fr.didelo.semaphore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A player's connection to a replica: what the server sends is parsed, kept, the latest room too.
 */
final class GameClient {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  final List<JsonNode> history = new CopyOnWriteArrayList<>();
  private final WebSocket socket;
  private volatile JsonNode room;
  private volatile Integer closeCode;
  private volatile String closeReason;

  GameClient(int port) {
    socket =
        HTTP.newWebSocketBuilder()
            .header("Origin", "https://play.didelo.fr")
            .buildAsync(
                URI.create("ws://localhost:" + port + "/ws"),
                new WebSocket.Listener() {
                  // A message may arrive in several parts: only the last one completes the JSON.
                  private final StringBuilder part = new StringBuilder();

                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    part.append(data);
                    if (last) {
                      var message = JSON.readTree(part.toString());
                      part.setLength(0);
                      var type = message.path("type").asString();
                      // Like the front: a view that arrives after a newer one is dropped.
                      if ((type.equals("joined") || type.equals("roomUpdated"))
                          && (room == null
                              || message.path("room").path("version").asInt()
                                  >= room.path("version").asInt())) {
                        room = message.path("room");
                      }
                      history.add(message);
                    }
                    ws.request(1);
                    return null;
                  }

                  @Override
                  public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                    closeCode = code;
                    closeReason = reason;
                    return null;
                  }
                })
            .join();
  }

  void send(String message) {
    socket.sendText(message, true).join();
  }

  JsonNode room() {
    return room;
  }

  /** The first {@code joined} message, which holds the token. */
  JsonNode joined() {
    return history.stream()
        .filter(m -> m.path("type").asString().equals("joined"))
        .findFirst()
        .orElse(null);
  }

  /** Whether an answer to the request {@code id} came back. */
  boolean gotAnswerTo(String id) {
    return history.stream().anyMatch(m -> m.path("id").asString().equals(id));
  }

  boolean gotError(String reason) {
    return history.stream()
        .anyMatch(
            m ->
                m.path("type").asString().equals("error")
                    && m.path("reason").asString().equals(reason));
  }

  Integer closeCode() {
    return closeCode;
  }

  String closeReason() {
    return closeReason;
  }

  void abort() {
    socket.abort();
  }
}
