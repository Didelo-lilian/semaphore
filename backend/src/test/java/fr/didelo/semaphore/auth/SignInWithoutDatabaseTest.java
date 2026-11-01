package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Accounts live in Postgres: a provider with its values but no database is no sign-in. */
@SpringBootTest(
    properties = {"semaphore.auth.github.client-id=a", "semaphore.auth.github.client-secret=b"})
class SignInWithoutDatabaseTest {

  @Autowired ApplicationContext context;

  @Test
  void noSignInBeanExists() {
    assertThat(context.getBeanNamesForType(Providers.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Accounts.class)).isEmpty();
    assertThat(context.getBeanNamesForType(AuthController.class)).isEmpty();
  }
}
