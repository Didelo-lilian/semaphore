package fr.didelo.semaphore.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** What the history keeps of a game that has ended (ADR 0012). */
public record GameResult(
    UUID id, Instant endedAt, Team winner, EndReason endReason, List<Participant> participants) {

  public enum EndReason {
    /** A team has all its words revealed, whoever revealed the last one. */
    COMPLETE,
    /** A team has no flag left. */
    REEF,
    /** A team had no Signaleur for too long. */
    FORFEIT
  }

  public enum Role {
    SIGNALER,
    LOOKOUT
  }

  public record Participant(UUID playerId, String name, Team team, Role role, UUID accountId) {}

  /** The result of the game that {@code state} ended, a state with a {@code gameId}. */
  public static GameResult of(RoomState state) {
    var complete =
        Stream.of(Team.values())
            .anyMatch(
                team ->
                    state.board().stream()
                        .filter(card -> card.kind() == team.kind())
                        .allMatch(Card::revealed));
    var reason =
        complete
            ? EndReason.COMPLETE
            : state.flags().containsValue(0) ? EndReason.REEF : EndReason.FORFEIT;
    // The Signaleur is the first member of a team in the order they joined, for the whole grid.
    var signalers = EnumSet.noneOf(Team.class);
    var participants = new ArrayList<Participant>();
    for (var player : state.players()) {
      var role = signalers.add(player.team()) ? Role.SIGNALER : Role.LOOKOUT;
      participants.add(
          new Participant(
              UUID.fromString(player.id()),
              player.name(),
              player.team(),
              role,
              player.accountId()));
    }
    return new GameResult(state.gameId(), state.endedAt(), state.winner(), reason, participants);
  }

  /** A game is kept for the accounts that played it (ADR 0012): none, nothing to keep. */
  public boolean hasAccount() {
    return participants.stream().anyMatch(participant -> participant.accountId() != null);
  }
}
