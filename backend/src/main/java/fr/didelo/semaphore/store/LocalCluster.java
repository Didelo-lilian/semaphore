package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.Cluster;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A cluster of one: this replica is always alive and always takes the lock. */
@Component
@WithoutRedis
public class LocalCluster implements Cluster {

  private final String id = UUID.randomUUID().toString();

  @Override
  public String id() {
    return id;
  }

  @Override
  public Set<String> alive() {
    return Set.of(id);
  }

  @Override
  public boolean lock(String name, Duration lifetime) {
    return true;
  }
}
