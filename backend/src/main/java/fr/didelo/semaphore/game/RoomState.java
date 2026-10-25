package fr.didelo.semaphore.game;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What is kept of a room between two changes: the state of a game, without the rules or the
 * settings, which {@link Room} gets from the application. It is what a store saves, as JSON in
 * Redis, with the hidden cards and the tokens in it. During a rolling deploy two versions of the
 * backend read the same rooms: a field is added one release before anything depends on it, and
 * removed one release after nobody writes it (ADR 0008).
 */
public record RoomState(
    String code,
    int version,
    Phase phase,
    Team turn,
    ActiveSignal signal,
    int revealsLeft,
    Team winner,
    Map<Team, Integer> flags,
    List<Card> board,
    List<PlayerState> players,
    Map<String, Integer> spectators,
    Instant lastActivity,
    Instant lastMove) {

  public record PlayerState(
      String id,
      String token,
      String name,
      Team team,
      Instant absentSince,
      boolean left,
      String replica) {}
}
