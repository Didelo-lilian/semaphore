package fr.didelo.semaphore.store;

import fr.didelo.semaphore.auth.LoginRequests;
import fr.didelo.semaphore.auth.WithSignIn;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A key {@code login:{id}} with a time to live: a login nobody finishes leaves by itself. */
@Component
@WithRedis
@WithSignIn
@RequiredArgsConstructor
class RedisLoginRequests implements LoginRequests {

  private static final String PREFIX = "login:";

  private final RedisLink link;

  @Override
  public void put(String id, String json) {
    link.run(commands -> commands.setex(PREFIX + id, LIFETIME.toSeconds(), json));
  }

  @Override
  public Optional<String> get(String id) {
    return Optional.ofNullable(link.run(commands -> commands.get(PREFIX + id)));
  }

  @Override
  public Optional<String> take(String id) {
    return Optional.ofNullable(link.run(commands -> commands.getdel(PREFIX + id)));
  }
}
