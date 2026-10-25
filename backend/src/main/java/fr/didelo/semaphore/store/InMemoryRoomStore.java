package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.RoomStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** The rooms of one process: development, the unit tests and an instance without Redis. */
@Component
public class InMemoryRoomStore implements RoomStore {

  private final ConcurrentMap<String, RoomState> rooms = new ConcurrentHashMap<>();

  @Override
  public Optional<RoomState> find(String code) {
    return Optional.ofNullable(rooms.get(code));
  }

  @Override
  public boolean create(RoomState room) {
    return rooms.putIfAbsent(room.code(), room) == null;
  }

  @Override
  public boolean replace(RoomState room, int loadedVersion) {
    var current = rooms.get(room.code());
    return current != null
        && current.version() == loadedVersion
        && rooms.replace(room.code(), current, room);
  }

  @Override
  public List<String> codes() {
    return List.copyOf(rooms.keySet());
  }

  @Override
  public boolean drop(String code, int version) {
    var current = rooms.get(code);
    return current != null && current.version() == version && rooms.remove(code, current);
  }
}
