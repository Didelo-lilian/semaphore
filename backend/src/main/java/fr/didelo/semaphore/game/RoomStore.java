package fr.didelo.semaphore.game;

import java.util.List;
import java.util.Optional;

/**
 * Where the rooms are kept, one {@link RoomState} per code. A store never changes a state: the
 * registry loads one, applies a command in Java and saves it back only if nobody saved a newer one
 * meanwhile (compare-and-set on the version), which is what replaces a lock on the room.
 */
public interface RoomStore {

  Optional<RoomState> find(String code);

  /** Saves a new room; false when the code is taken. */
  boolean create(RoomState room);

  /**
   * Saves {@code room} if the stored one is still at {@code loadedVersion}; false when it is not.
   */
  boolean replace(RoomState room, int loadedVersion);

  List<String> codes();

  /** Removes the room if it is still at {@code version}: one that was joined meanwhile stays. */
  boolean drop(String code, int version);
}
