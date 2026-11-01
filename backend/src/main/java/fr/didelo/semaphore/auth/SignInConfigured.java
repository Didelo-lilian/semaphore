package fr.didelo.semaphore.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Accounts live in Postgres, so without a database nobody can sign in; and a provider with a blank
 * id or secret is off (ADR 0011), so the game plays as before until the values are there.
 */
class SignInConfigured implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    var environment = context.getEnvironment();
    return !environment.getProperty("spring.datasource.url", "").isBlank()
        && !Providers.registrations(environment).isEmpty();
  }
}
