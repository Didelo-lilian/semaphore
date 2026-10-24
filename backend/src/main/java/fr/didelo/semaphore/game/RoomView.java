package fr.didelo.semaphore.game;

import java.util.List;
import java.util.Map;

/**
 * A room as one player may see it: the kind of a card is null until it is revealed, unless they
 * hold the key.
 */
public record RoomView(
    String code,
    Phase phase,
    Team turn,
    ActiveSignal signal,
    int revealsLeft,
    Map<Team, Integer> flags,
    Team winner,
    List<PlayerView> players,
    List<CardView> board,
    String you) {

  public record PlayerView(
      String id, String name, Team team, boolean connected, boolean host, boolean signaler) {}

  public record CardView(String word, boolean revealed, CardKind kind) {}
}
