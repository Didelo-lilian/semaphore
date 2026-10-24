package fr.didelo.semaphore.game;

import java.util.List;
import java.util.Map;

/**
 * A room as one player may see it: the kind of a card is null until it is revealed, unless they
 * hold the key. {@code turnPassesIn} is the seconds left, when the view is built, before the turn
 * passes because the game waits for someone who is away; null when it waits for nobody away. {@code
 * you} is null in a spectator's view, who has no seat; {@code spectators} counts them.
 */
public record RoomView(
    String code,
    int version,
    Phase phase,
    Team turn,
    Integer turnPassesIn,
    ActiveSignal signal,
    int revealsLeft,
    Map<Team, Integer> flags,
    Team winner,
    List<PlayerView> players,
    int spectators,
    List<CardView> board,
    String you) {

  public record PlayerView(
      String id, String name, Team team, boolean connected, boolean host, boolean signaler) {}

  public record CardView(String word, boolean revealed, CardKind kind) {}
}
