package fr.didelo.semaphore.game;

import java.time.Duration;
import java.util.Set;

/** The replicas of the backend, as far as presence and the sweeps need to know them. */
public interface Cluster {

  /** The id this replica carries on every player whose connection it holds. */
  String id();

  /** The replicas whose heartbeat has not run out, this one included once it has beaten. */
  Set<String> alive();

  /**
   * Whether this replica takes {@code name} for {@code lifetime}. The lock is never released: it
   * runs out, so that one replica per tick does a sweep, and a replica that died holding it blocks
   * nobody for long.
   */
  boolean lock(String name, Duration lifetime);
}
