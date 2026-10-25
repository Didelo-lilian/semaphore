package fr.didelo.semaphore.game;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a replica tells the others, always with its own id. It never carries a room: a replica that
 * has a connection in the room reads it from the store, so the hidden cards stay out of the channel
 * (ADR 0008).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RoomEvent.Changed.class, name = "changed"),
  @JsonSubTypes.Type(value = RoomEvent.SeatTaken.class, name = "seatTaken")
})
public sealed interface RoomEvent {

  String replica();

  /** The room is now at {@code version}: whoever holds a connection of it sends the views. */
  record Changed(String code, int version, String replica) implements RoomEvent {}

  /** The player took their seat back on {@code replica}: an older connection of theirs is stale. */
  record SeatTaken(String code, String player, String replica) implements RoomEvent {}
}
