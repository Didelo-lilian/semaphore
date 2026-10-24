package fr.didelo.semaphore.game;

import java.time.Instant;
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
  // Null while the player is connected.
  @Setter private Instant absentSince;
  // Absent on purpose (`leave`): the game does not wait for them.
  @Setter private boolean left;

  public boolean isConnected() {
    return absentSince == null;
  }
}
