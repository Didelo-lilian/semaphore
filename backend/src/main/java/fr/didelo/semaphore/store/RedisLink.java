package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.Unavailable;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The connection to Redis that the store, the events and the cluster share. It connects when it is
 * first needed and again after each failure, so a backend that starts before Redis does comes up
 * and finds it later. While Redis cannot be reached a command fails as {@link Unavailable}: at once
 * for a second after a failed attempt, and else after the attempt that is running, one second at
 * most.
 */
@Slf4j
@Component
@WithRedis
class RedisLink {

  static final String BUSY = "the server is busy, try again";
  private static final long CONNECT_RETRY_MS = 1_000;
  static final String FULL = "the server is full, try again later";

  private final ClientResources resources =
      DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
  private final RedisClient client;
  private volatile StatefulRedisConnection<String, String> connection;
  private volatile boolean reachable = true;
  // Until then a command fails at once instead of waiting for its own try to connect.
  private volatile long retryAt;

  RedisLink(@Value("${" + RedisConfigured.URL + "}") String url) {
    var uri = RedisURI.create(url);
    uri.setTimeout(Duration.ofSeconds(2));
    client = RedisClient.create(resources, uri);
    client.setOptions(
        ClientOptions.builder()
            .protocolVersion(ProtocolVersion.RESP2)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
            .build());
  }

  RedisClient client() {
    return client;
  }

  /** Runs commands, translating what Redis or the network says into {@link Unavailable}. */
  <T> T run(Function<RedisCommands<String, String>, T> commands) {
    try {
      var result = commands.apply(connection().sync());
      if (!reachable) {
        reachable = true;
        log.info("Redis answers again");
      }
      return result;
    } catch (RedisCommandExecutionException e) {
      // Redis is there and refuses; with maxmemory and noeviction, OOM is a write it has no room
      // for.
      log.warn("Redis refused a command: {}", e.getMessage());
      throw new Unavailable(e.getMessage().startsWith("OOM") ? FULL : BUSY, e);
    } catch (RedisException e) {
      if (reachable) {
        reachable = false;
        log.warn("Redis cannot be reached: {}", e.toString());
      }
      throw new Unavailable(BUSY, e);
    }
  }

  /** The names of the keys that start with {@code prefix}, without it. */
  Set<String> keys(String prefix) {
    return run(
        commands -> {
          var names = new HashSet<String>();
          var args = ScanArgs.Builder.matches(prefix + "*").limit(1_000);
          var cursor = ScanCursor.INITIAL;
          do {
            var page = commands.scan(cursor, args);
            page.getKeys().forEach(key -> names.add(key.substring(prefix.length())));
            cursor = page;
          } while (!cursor.isFinished());
          return names;
        });
  }

  private StatefulRedisConnection<String, String> connection() {
    var current = connection;
    if (current == null) {
      synchronized (this) {
        if (connection == null) {
          if (System.currentTimeMillis() < retryAt) {
            throw new RedisConnectionException("Redis could not be reached a moment ago");
          }
          try {
            connection = client.connect();
          } catch (RedisConnectionException e) {
            retryAt = System.currentTimeMillis() + CONNECT_RETRY_MS;
            throw e;
          }
        }
        current = connection;
      }
    }
    return current;
  }

  @PreDestroy
  void close() {
    client.shutdown(0, 2, TimeUnit.SECONDS);
    resources.shutdown(0, 2, TimeUnit.SECONDS);
  }
}
