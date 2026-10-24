package fr.didelo.semaphore.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** What the server sends. The front validates each one with a matching Zod schema. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ServerMessage.Echoed.class, name = "echoed"),
  @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error")
})
public sealed interface ServerMessage {

  record Echoed(String text) implements ServerMessage {}

  record Error(String reason) implements ServerMessage {}
}
