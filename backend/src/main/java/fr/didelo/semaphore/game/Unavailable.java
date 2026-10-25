package fr.didelo.semaphore.game;

/**
 * The rooms could not be reached or changed, whatever the rules say: the store is down or full, or
 * too many changes crossed one another. The reason is sent to the player who asked, and nothing was
 * changed.
 */
public class Unavailable extends RuntimeException {

  public Unavailable(String reason) {
    super(reason);
  }

  public Unavailable(String reason, Throwable cause) {
    super(reason, cause);
  }
}
