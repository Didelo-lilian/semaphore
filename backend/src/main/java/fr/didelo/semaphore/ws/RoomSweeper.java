package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.game.Cluster;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Unavailable;
import java.time.Duration;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What time and absence do to the rooms. Each sweep runs on one replica per tick, the one that
 * takes the lock, and may run twice without harm: it loads each room again and saves its change
 * only if nobody saved another meanwhile.
 */
@Component
@RequiredArgsConstructor
public class RoomSweeper {

  // A lock lasts less than the tick that takes it, so that the next tick finds it free.
  private static final Duration EXPIRE_LOCK = Duration.ofMillis(900);
  private static final Duration DROP_ABSENT_LOCK = Duration.ofMillis(4_500);
  private static final Duration REPLICAS_LOCK = Duration.ofMillis(4_500);
  private static final Duration ABANDONED_LOCK = Duration.ofSeconds(55);

  private final RoomRegistry rooms;
  private final Cluster cluster;
  private final GameSocketHandler handler;

  /** The turn passes, or the game ends, when the game has waited long enough for someone away. */
  @Scheduled(fixedDelay = 1_000)
  void expire() {
    sweep("expire", EXPIRE_LOCK, Room::expire);
  }

  /** The lobby keeps an absent player for a while: once it is over, the others see them go. */
  @Scheduled(fixedDelay = 5_000)
  void dropAbsent() {
    sweep("dropAbsent", DROP_ABSENT_LOCK, Room::dropAbsent);
  }

  /**
   * A replica that stopped beating holds no connection any more: its players are marked absent, so
   * the usual graces apply, and its spectators are forgotten. A replica that is not itself beating
   * knows nothing about the others.
   */
  @Scheduled(fixedDelay = 5_000)
  void deadReplicas() {
    try {
      var alive = cluster.alive();
      if (alive.contains(cluster.id())) {
        sweep("replicas", REPLICAS_LOCK, room -> room.dropReplicasNotIn(alive));
      }
    } catch (Unavailable e) {
      // Nothing can be told apart from a replica that died while the store is out of reach.
    }
  }

  @Scheduled(fixedDelay = 60_000)
  void dropAbandoned() {
    try {
      if (cluster.lock("abandoned", ABANDONED_LOCK)) {
        rooms.dropAbandoned();
      }
    } catch (Unavailable e) {
      // The next sweep tries again.
    }
  }

  private void sweep(String name, Duration lock, Consumer<Room> change) {
    try {
      if (!cluster.lock(name, lock)) {
        return;
      }
      for (var code : rooms.codes()) {
        try {
          var swept = rooms.change(code, change);
          if (swept.changed()) {
            handler.roomChanged(swept.room());
          }
        } catch (IllegalMove | Unavailable e) {
          // The room was dropped since the list was made, or cannot be read or saved now: the next
          // tick tries again, and the other rooms go on.
        }
      }
    } catch (Unavailable e) {
      // The next tick tries again.
    }
  }
}
