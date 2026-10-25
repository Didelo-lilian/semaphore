package fr.didelo.semaphore.game;

/**
 * Told of a game that has just ended, by the replica that ended it, and once more when its
 * abandoned room is dropped.
 */
public interface GameEndListener {

  /** Must not throw and must not wait: the room has been saved, and the players are waiting. */
  void ended(RoomState state);
}
