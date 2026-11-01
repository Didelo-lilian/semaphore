package fr.didelo.semaphore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProvidersTest {

  private static MockEnvironment environment(String... pairs) {
    var environment = new MockEnvironment();
    for (var i = 0; i < pairs.length; i += 2) {
      environment.setProperty("semaphore.auth." + pairs[i], pairs[i + 1]);
    }
    return environment;
  }

  @Test
  void aProviderIsOnOnlyWithItsIdAndItsSecret() {
    var environment =
        environment(
            "github.client-id", "id",
            "github.client-secret", " ",
            "google.client-id", "",
            "google.client-secret", "secret");

    assertThat(Providers.of(environment).registrations()).isEmpty();
  }

  @Test
  void githubAsksForNoScopeAndGoogleForOpenidOnly() {
    var environment =
        environment(
            "github.client-id", "a",
            "github.client-secret", "b",
            "google.client-id", "c",
            "google.client-secret", "d");

    var registrations = Providers.of(environment).registrations();

    assertThat(registrations).containsOnlyKeys("github", "google");
    assertThat(registrations.get("github").getScopes()).isEmpty();
    assertThat(registrations.get("google").getScopes()).containsExactly("openid");
    assertThat(registrations.get("github").getRedirectUri())
        .isEqualTo("{baseUrl}/api/auth/callback/{registrationId}");
  }

  @Test
  void theBuiltInEndpointsAreReplacedOnlyWhenTheSettingsSayIt() {
    var environment =
        environment(
            "github.client-id", "a",
            "github.client-secret", "b",
            "github.token-uri", "http://idp.test/token");

    var github = Providers.of(environment).registrations().get("github");

    assertThat(github.getProviderDetails().getTokenUri()).isEqualTo("http://idp.test/token");
    assertThat(github.getProviderDetails().getAuthorizationUri())
        .isEqualTo("https://github.com/login/oauth/authorize");
  }
}
