package fr.didelo.semaphore.game;

/** What a player is called: in a room, and as a guest (ADR 0010). */
public final class PlayerName {

  private static final int MAX_LENGTH = 20;

  private PlayerName() {}

  /**
   * The name without its surrounding spaces; refused when empty, too long, or holding a control or
   * format character (a NUL cannot be stored in Postgres text, and none belongs in a pseudo; a
   * format character such as the right-to-left override would turn a name shown to others around).
   */
  public static String of(String raw) {
    var name = raw.strip();
    if (name.isEmpty() || name.length() > MAX_LENGTH) {
      throw new IllegalMove("a name has 1 to " + MAX_LENGTH + " characters");
    }
    if (name.codePoints()
        .anyMatch(
            point ->
                Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)) {
      throw new IllegalMove("a name has no control or format character");
    }
    return name;
  }
}
