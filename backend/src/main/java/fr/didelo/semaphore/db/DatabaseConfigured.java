package fr.didelo.semaphore.db;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@code spring.datasource.url} is set to something: an empty value means no database. */
class DatabaseConfigured implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !context.getEnvironment().getProperty("spring.datasource.url", "").isBlank();
  }
}
