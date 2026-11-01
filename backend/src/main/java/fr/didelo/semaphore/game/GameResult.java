package fr.didelo.semaphore.game;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the history keeps of a game that has ended, made by {@link Room#result()} (ADR 0012). */
public record GameResult(
    UUID id, Instant endedAt, Team winner, EndReason endReason, List<Participant> participants) {

  public enum Role {
    SIGNALER,
    LOOKOUT
  }

  public record Participant(UUID playerId, String name, Team team, Role role, UUID accountId) {}

  /** A game is kept for the accounts that played it (ADR 0012): none, nothing to keep. */
  public boolean hasAccount() {
    return participants.stream().anyMatch(participant -> participant.accountId() != null);
  }
}
