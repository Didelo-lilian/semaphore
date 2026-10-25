package fr.didelo.semaphore.game;

/** What a player is called: in a room, and as a guest (ADR 0010). */
public final class PlayerName {

  private static final int MAX_LENGTH = 20;

  private PlayerName() {}

  /** The name without its surrounding spaces; refused when empty or too long. */
  public static String of(String raw) {
    var name = raw.strip();
    if (name.isEmpty() || name.length() > MAX_LENGTH) {
      throw new IllegalMove("a name has 1 to " + MAX_LENGTH + " characters");
    }
    return name;
  }
}
