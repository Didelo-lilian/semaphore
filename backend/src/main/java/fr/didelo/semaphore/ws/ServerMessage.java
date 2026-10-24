package fr.didelo.semaphore.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import fr.didelo.semaphore.game.RoomView;

/**
 * What the server sends. The front validates each one with a matching Zod schema. The {@code id} is
 * the one of the client message this answers, and is absent from what goes to the other players.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ServerMessage.Joined.class, name = "joined"),
  @JsonSubTypes.Type(value = ServerMessage.RoomUpdated.class, name = "roomUpdated"),
  @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error")
})
public sealed interface ServerMessage {

  /** Sent once to the player who got in; the token is how they come back. */
  record Joined(String id, String playerId, String token, RoomView room) implements ServerMessage {}

  /** Sent to every player of a room after each change, each with their own view. */
  record RoomUpdated(String id, RoomView room) implements ServerMessage {}

  record Error(String id, String reason) implements ServerMessage {}
}
