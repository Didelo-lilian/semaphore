package fr.didelo.semaphore.game;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public final class Player {

  private final String id;
  private final String token;
  private final String name;

  @Setter private Team team;
  // The account the player signed in with, null for a guest.
  @Setter private UUID accountId;
  // Null while the player is connected.
  @Setter private Instant absentSince;
  // Absent on purpose (`leave`): the game does not wait for them.
  @Setter private boolean left;
  // The replica that holds the connection, null while absent: the sweep marks absent whoever's
  // replica has stopped beating.
  @Setter private String replica;

  // The name of the level of a bot, null for a human (ADR 0016).
  @Setter private String bot;

  public boolean isBot() {
    return bot != null;
  }

  /** A bot has no connection to lose: nobody waits for it and nobody marks it absent. */
  public boolean isConnected() {
    return isBot() || absentSince == null;
  }
}
