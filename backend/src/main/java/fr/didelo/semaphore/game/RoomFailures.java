package fr.didelo.semaphore.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the sweeps and the checks report a room that failed. They run every second or every few
 * seconds and a room that is broken stays broken: the stack of a failure is logged the first time a
 * room fails that way, and the same failure after it is only a debug line.
 */
@Slf4j
public final class RoomFailures {

  private static final Map<String, String> LAST = new ConcurrentHashMap<>();

  private RoomFailures() {}

  public static void log(String code, String doing, RuntimeException failure) {
    if (failure.toString().equals(LAST.put(code, failure.toString()))) {
      log.debug("Room {} still could not be {}: {}", code, doing, failure.toString());
    } else {
      log.warn("Room {} could not be {}", code, doing, failure);
    }
  }
}
