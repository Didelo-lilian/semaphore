package fr.didelo.semaphore.game;

/** How a game ended, kept in the state of the room for the history (ADR 0012). */
public enum EndReason {
  /** A team has all its words revealed, whoever revealed the last one. */
  COMPLETE,
  /** A team has no flag left. */
  REEF,
  /** A team had no Signaleur for too long. */
  FORFEIT
}
