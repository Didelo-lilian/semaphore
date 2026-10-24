package fr.didelo.semaphore.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import fr.didelo.semaphore.game.RoomView;

/** What the server sends. The front validates each one with a matching Zod schema. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ServerMessage.Joined.class, name = "joined"),
  @JsonSubTypes.Type(value = ServerMessage.RoomUpdated.class, name = "roomUpdated"),
  @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error")
})
public sealed interface ServerMessage {

  /** Sent once to the player who got in; the token is how they come back. */
  record Joined(String playerId, String token, RoomView room) implements ServerMessage {}

  /** Sent to every player of a room after each change, each with their own view. */
  record RoomUpdated(RoomView room) implements ServerMessage {}

  record Error(String reason) implements ServerMessage {}
}
