package fr.didelo.semaphore.store;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisClusterTest {

  @Test
  void theHeartbeatGoesOnAfterAnythingIsThrown() {
    var link = mock(RedisLink.class);
    doThrow(new StackOverflowError("deep"))
        .doThrow(new IllegalStateException("odd"))
        .when(link)
        .run(any());
    var cluster = new RedisCluster(link, Duration.ofMillis(30));

    cluster.start();
    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> verify(link, atLeast(4)).run(any()));
    } finally {
      cluster.stop();
    }
  }
}
