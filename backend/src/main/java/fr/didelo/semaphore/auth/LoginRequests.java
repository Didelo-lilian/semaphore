package fr.didelo.semaphore.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * Where a login in flight waits for the browser to come back from GitHub or Google (ADR 0011): in
 * Valkey, so either replica finds it, and for ten minutes at most. Not in the session, since a
 * visitor has none until they choose a pseudo and the request holds objects that must never become
 * session state.
 */
public interface LoginRequests {

  Duration LIFETIME = Duration.ofMinutes(10);

  void put(String id, String json);

  Optional<String> get(String id);

  /** Reads it and deletes it: a request is used once. */
  Optional<String> take(String id);
}
