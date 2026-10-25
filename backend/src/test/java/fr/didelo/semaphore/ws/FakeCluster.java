package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Cluster;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A replica of a cluster the test steers: who beats, and which locks are taken in this tick. */
final class FakeCluster implements Cluster {

  /** What the replicas of one test share, as they would share Redis. */
  static final class Shared {
    final Set<String> alive = ConcurrentHashMap.newKeySet();
    final Set<String> locks = ConcurrentHashMap.newKeySet();

    /** The locks of the last tick have run out. */
    void tick() {
      locks.clear();
    }
  }

  private final String id;
  private final Shared shared;

  FakeCluster(String id, Shared shared) {
    this.id = id;
    this.shared = shared;
    shared.alive.add(id);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public Set<String> alive() {
    return Set.copyOf(shared.alive);
  }

  @Override
  public boolean lock(String name, Duration lifetime) {
    return shared.locks.add(name);
  }
}
