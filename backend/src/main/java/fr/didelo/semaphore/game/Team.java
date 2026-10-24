package fr.didelo.semaphore.game;

public enum Team {
  BABORD,
  TRIBORD;

  public Team other() {
    return this == BABORD ? TRIBORD : BABORD;
  }

  public CardKind kind() {
    return this == BABORD ? CardKind.BABORD : CardKind.TRIBORD;
  }
}
