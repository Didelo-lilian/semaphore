package fr.didelo.semaphore.game;

import java.util.function.Consumer;

/** How the replicas wake each other up when a room changes. */
public interface RoomEvents {

  /**
   * Tells every replica, this one included. It does not fail: a message that does not get through
   * is repaired by the periodic check of versions.
   */
  void publish(RoomEvent event);

  /**
   * Calls {@code listener} for every event of every replica. {@code subscribed} runs once the
   * subscription is in place and again each time it comes back after a break, since events sent
   * meanwhile are lost.
   */
  void subscribe(Consumer<RoomEvent> listener, Runnable subscribed);
}
