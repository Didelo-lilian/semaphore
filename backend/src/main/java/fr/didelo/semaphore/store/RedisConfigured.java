package fr.didelo.semaphore.store;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@code SEMAPHORE_REDIS_URL} is set to something: an empty value means no Redis, like none. */
class RedisConfigured implements Condition {

  static final String URL = "semaphore.redis-url";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !context.getEnvironment().getProperty(URL, "").isBlank();
  }

  static class Not implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return !new RedisConfigured().matches(context, metadata);
    }
  }
}
