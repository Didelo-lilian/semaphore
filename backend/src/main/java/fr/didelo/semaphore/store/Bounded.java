package fr.didelo.semaphore.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map in the memory of one process for what nobody reads again if it is forgotten (sessions,
 * logins in flight): past {@code max} entries the oldest go.
 */
final class Bounded {

  private Bounded() {}

  static <K, V> Map<K, V> map(int max) {
    return Collections.synchronizedMap(
        new LinkedHashMap<>() {
          @Override
          protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
          }
        });
  }
}
