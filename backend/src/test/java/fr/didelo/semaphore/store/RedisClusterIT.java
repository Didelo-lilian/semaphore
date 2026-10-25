package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.didelo.semaphore.Valkey;
import fr.didelo.semaphore.game.RoomEvent;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The heartbeat, the lock and the channel against a real Valkey. */
class RedisClusterIT {

  private static RedisLink link;

  @BeforeAll
  static void connect() {
    link = new RedisLink(Valkey.url());
  }

  @AfterAll
  static void disconnect() {
    link.close();
  }

  @Test
  void aReplicaIsAliveWhileItBeatsAndNotAfterItsHeartbeatRunsOut() throws Exception {
    var cluster = new RedisCluster(link, Duration.ofSeconds(1));

    cluster.beat();

    assertThat(cluster.alive()).contains(cluster.id());
    await().atMost(Duration.ofSeconds(5)).until(() -> !cluster.alive().contains(cluster.id()));
  }

  @Test
  void aStartedReplicaKeepsBeatingUntilItStops() {
    var cluster = new RedisCluster(link, Duration.ofSeconds(3));

    cluster.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> cluster.alive().contains(cluster.id()));
      await()
          .during(Duration.ofSeconds(5))
          .atMost(Duration.ofSeconds(8))
          .until(() -> cluster.alive().contains(cluster.id()));
    } finally {
      cluster.stop();
    }

    await().atMost(Duration.ofSeconds(8)).until(() -> !cluster.alive().contains(cluster.id()));
  }

  @Test
  void aLockIsTakenByOneReplicaUntilItRunsOut() {
    var first = new RedisCluster(link, Duration.ofSeconds(15));
    var second = new RedisCluster(link, Duration.ofSeconds(15));
    var name = "test-" + first.id();

    assertThat(first.lock(name, Duration.ofSeconds(1))).isTrue();
    assertThat(second.lock(name, Duration.ofSeconds(1))).isFalse();
    await().atMost(Duration.ofSeconds(5)).until(() -> second.lock(name, Duration.ofSeconds(5)));
  }

  @Test
  void anEventReachesTheOtherSubscribersAndTheSubscriptionComesBackAfterABreak() {
    var json = JsonMapper.builder().build();
    var sender = new RedisRoomEvents(link, json);
    var receiver = new RedisRoomEvents(link, json);
    var received = new CopyOnWriteArrayList<RoomEvent>();
    var subscriptions = new AtomicInteger();
    receiver.subscribe(received::add, subscriptions::incrementAndGet);
    await().atMost(Duration.ofSeconds(5)).until(() -> subscriptions.get() == 1);

    sender.publish(new RoomEvent.Changed("ABCDE", 4, "replica-1"));
    sender.publish(new RoomEvent.SeatTaken("ABCDE", "p1", "replica-1"));

    await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
    assertThat(received)
        .containsExactlyInAnyOrder(
            new RoomEvent.Changed("ABCDE", 4, "replica-1"),
            new RoomEvent.SeatTaken("ABCDE", "p1", "replica-1"));
    try (var admin = link.client().connect()) {
      admin.sync().clientKill(io.lettuce.core.KillArgs.Builder.typePubsub());
    }
    await().atMost(Duration.ofSeconds(10)).until(() -> subscriptions.get() == 2);
    sender.publish(new RoomEvent.Changed("ABCDE", 5, "replica-1"));
    await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 3);
    sender.close();
    receiver.close();
  }

  @Test
  void theHeartbeatAndTheLocksStillWorkWhenRedisIsFullOfMemory() {
    var cluster = new RedisCluster(link, Duration.ofSeconds(15));
    try (var admin = link.client().connect()) {
      admin.sync().configSet("maxmemory", "1");
      try {
        cluster.beat();

        assertThat(cluster.alive()).contains(cluster.id());
        assertThat(cluster.lock("full-" + cluster.id(), Duration.ofSeconds(5))).isTrue();
      } finally {
        admin.sync().configSet("maxmemory", "64mb");
      }
    }
  }
}
