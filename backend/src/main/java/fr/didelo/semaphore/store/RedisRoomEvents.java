package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.RoomEvent;
import fr.didelo.semaphore.game.RoomEvents;
import fr.didelo.semaphore.game.Unavailable;
import io.lettuce.core.RedisException;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Redis channel. What the listeners do (reading a room, sending views) is blocking, so it runs
 * off the thread of the Redis client, which would otherwise wait for itself.
 */
@Slf4j
@Component
@WithRedis
@RequiredArgsConstructor
class RedisRoomEvents implements RoomEvents {

  private static final String CHANNEL = "rooms";
  private static final Duration RETRY = Duration.ofSeconds(1);

  private final RedisLink link;
  private final JsonMapper json;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile StatefulRedisPubSubConnection<String, String> subscription;

  @Override
  public void publish(RoomEvent event) {
    try {
      link.run(commands -> commands.publish(CHANNEL, json.writeValueAsString(event)));
    } catch (Unavailable e) {
      // The replicas that miss it catch up at their next check of versions.
    }
  }

  /**
   * Subscribes in the background, trying again until Redis answers. The client subscribes again by
   * itself after a break, and each confirmation tells {@code subscribed}: what was published
   * meanwhile is lost, so the listener checks the versions then.
   */
  @Override
  public void subscribe(Consumer<RoomEvent> listener, Runnable subscribed) {
    executor.execute(
        () -> {
          while (subscription == null && !Thread.currentThread().isInterrupted()) {
            StatefulRedisPubSubConnection<String, String> connection = null;
            try {
              connection = link.client().connectPubSub();
              connection.addListener(
                  new RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                      executor.execute(
                          () -> {
                            try {
                              listener.accept(json.readValue(message, RoomEvent.class));
                            } catch (JacksonException e) {
                              // An event of a newer version that this one does not know.
                            }
                          });
                    }

                    @Override
                    public void subscribed(String channel, long count) {
                      executor.execute(subscribed);
                    }
                  });
              connection.sync().subscribe(CHANNEL);
              subscription = connection;
            } catch (RedisException e) {
              log.debug("Cannot subscribe to the rooms channel yet: {}", e.toString());
              if (connection != null) {
                connection.close();
              }
              LockSupport.parkNanos(RETRY.toNanos());
            }
          }
        });
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
    if (subscription != null) {
      subscription.close();
    }
  }
}
