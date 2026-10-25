package fr.didelo.semaphore.game;

/** Told, once per replica that ends it, of a game that has just ended. */
public interface GameEndListener {

  /** Must not throw and must not wait: the room has been saved, and the players are waiting. */
  void ended(RoomState state);
}
