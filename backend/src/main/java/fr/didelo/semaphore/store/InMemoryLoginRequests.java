package fr.didelo.semaphore.store;

import fr.didelo.semaphore.auth.LoginRequests;
import fr.didelo.semaphore.auth.WithSignIn;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** One process: development and tests. The oldest go when there are too many. */
@Component
@WithoutRedis
@WithSignIn
class InMemoryLoginRequests implements LoginRequests {

  private static final int MAX_REQUESTS = 1_000;

  private record Waiting(String json, Instant until) {}

  private final Map<String, Waiting> waiting = Bounded.map(MAX_REQUESTS);

  @Override
  public void put(String id, String json) {
    waiting.put(id, new Waiting(json, Instant.now().plus(LIFETIME)));
  }

  @Override
  public Optional<String> get(String id) {
    return Optional.ofNullable(waiting.get(id)).filter(this::alive).map(Waiting::json);
  }

  @Override
  public Optional<String> take(String id) {
    return Optional.ofNullable(waiting.remove(id)).filter(this::alive).map(Waiting::json);
  }

  private boolean alive(Waiting request) {
    return request.until().isAfter(Instant.now());
  }
}
