package fr.didelo.semaphore.game;

/** A command the room refuses. The reason is sent to the player who asked. */
public class IllegalMove extends RuntimeException {

  public IllegalMove(String reason) {
    super(reason);
  }
}
