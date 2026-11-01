package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.guest.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/** Accounts live in Postgres: a provider with its values but no database is no sign-in. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"semaphore.auth.github.client-id=a", "semaphore.auth.github.client-secret=b"})
class SignInWithoutDatabaseTest {

  @Autowired ApplicationContext context;
  @LocalServerPort int port;

  @Test
  void noSignInBeanExists() {
    assertThat(context.getBeanNamesForType(Providers.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Accounts.class)).isEmpty();
    assertThat(context.getBeanNamesForType(AuthController.class)).isEmpty();
  }

  @Test
  void noRouteStartsALogin() {
    var browser = new Browser(port);

    assertThat(browser.request("GET", "/api/auth/login/github").statusCode()).isEqualTo(404);
    assertThat(browser.request("GET", "/api/auth/callback/github?code=a&state=b").statusCode())
        .isEqualTo(404);
  }
}
