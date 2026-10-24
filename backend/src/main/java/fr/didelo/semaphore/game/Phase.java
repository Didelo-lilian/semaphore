package fr.didelo.semaphore.game;

import java.util.Set;

/** The legal transitions of a room live here: a command is only ever allowed in these phases. */
public enum Phase {
  LOBBY(Set.of(Command.JoinTeam.class, Command.Start.class)),
  SIGNAL(Set.of(Command.Signal.class)),
  GUESS(Set.of(Command.Reveal.class, Command.Pass.class)),
  ENDED(Set.of());

  private final Set<Class<? extends Command>> allowed;

  Phase(Set<Class<? extends Command>> allowed) {
    this.allowed = allowed;
  }

  public boolean allows(Command command) {
    return allowed.contains(command.getClass());
  }
}
