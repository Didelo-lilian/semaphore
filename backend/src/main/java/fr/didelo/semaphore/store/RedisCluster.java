package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.Cluster;
import fr.didelo.semaphore.game.Unavailable;
import io.lettuce.core.ScriptOutputType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Each replica renews a key {@code replica:{id}} that lives 15 seconds, every 5 seconds, from a
 * thread of its own: the sweeps wait on Redis and on slow connections, and a heartbeat behind them
 * would make the replica look dead. A replica is alive as long as its key is there. A lock is a key
 * set only if absent, with a lifetime.
 */
@Slf4j
@Component
@WithRedis
class RedisCluster implements Cluster {

  private static final String BEAT = "replica:";
  private static final String LOCK = "lock:";
  // Both keys are tiny and must not be refused when Redis is full: a heartbeat that stops makes
  // every player of the replica look gone, and a lock that cannot be taken stops the sweeps that
  // would free memory.
  private static final String BEAT_SCRIPT =
      """
      #!lua flags=allow-oom
      return redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
      """;
  private static final String LOCK_SCRIPT =
      """
      #!lua flags=allow-oom
      return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
      """;

  private final String id = UUID.randomUUID().toString();
  private final RedisLink link;
  private final Duration heartbeatLife;
  private final ScheduledExecutorService heartbeat =
      Executors.newSingleThreadScheduledExecutor(
          task -> Thread.ofPlatform().name("semaphore-heartbeat").daemon().unstarted(task));

  RedisCluster(RedisLink link, @Value("${semaphore.heartbeat-life:15s}") Duration heartbeatLife) {
    this.link = link;
    this.heartbeatLife = heartbeatLife;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public Set<String> alive() {
    return link.keys(BEAT);
  }

  @Override
  public boolean lock(String name, Duration lifetime) {
    String taken =
        link.run(
            commands ->
                commands.eval(
                    LOCK_SCRIPT,
                    ScriptOutputType.STATUS,
                    new String[] {LOCK + name},
                    id,
                    String.valueOf(lifetime.toMillis())));
    return taken != null;
  }

  @PostConstruct
  void start() {
    var every = heartbeatLife.dividedBy(3).toMillis();
    heartbeat.scheduleWithFixedDelay(this::beat, 0, every, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stop() {
    heartbeat.shutdownNow();
  }

  void beat() {
    try {
      link.run(
          commands ->
              commands.eval(
                  BEAT_SCRIPT,
                  ScriptOutputType.STATUS,
                  new String[] {BEAT + id},
                  String.valueOf(heartbeatLife.toMillis())));
    } catch (Unavailable e) {
      // Redis may start after this replica: the next beat tries again.
    } catch (Throwable e) {
      // Whatever is thrown ends the schedule for good: the replica would look dead while it lives.
      log.warn("The heartbeat failed", e);
    }
  }
}
