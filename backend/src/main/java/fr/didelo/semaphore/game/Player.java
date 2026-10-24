package fr.didelo.semaphore.game;

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
  @Setter private boolean connected = true;
}
