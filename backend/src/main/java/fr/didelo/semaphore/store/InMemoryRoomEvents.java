package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.RoomEvent;
import fr.didelo.semaphore.game.RoomEvents;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Events inside one process: the listeners are called at once, in the thread that publishes. */
@Component
@WithoutRedis
public class InMemoryRoomEvents implements RoomEvents {

  private final List<Consumer<RoomEvent>> listeners = new CopyOnWriteArrayList<>();

  @Override
  public void publish(RoomEvent event) {
    listeners.forEach(listener -> listener.accept(event));
  }

  @Override
  public void subscribe(Consumer<RoomEvent> listener, Runnable subscribed) {
    listeners.add(listener);
    subscribed.run();
  }
}
