package fr.didelo.semaphore.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

/**
 * The providers that are on: GitHub and Google with an id and a secret that are not blank (ADR
 * 0011). They ask for the least there is: Google the scope {@code openid} alone, whose {@code sub}
 * is all that is kept, and GitHub no scope, the public profile whose numeric {@code id} is stable.
 * The endpoints are the built-in ones, no discovery, so a provider that is down cannot stop the
 * backend from starting; the properties can replace them (tests, GitHub Enterprise).
 */
@Component
@WithSignIn
public class Providers {

  static final String LOGIN = "/api/auth/login";
  static final String CALLBACK = "/api/auth/callback";

  private static final List<String> NAMES = List.of("github", "google");

  record Settings(
      String clientId,
      String clientSecret,
      String authorizationUri,
      String tokenUri,
      String userInfoUri,
      String jwkSetUri,
      String issuerUri) {

    boolean on() {
      return clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }
  }

  private final Map<String, ClientRegistration> registrations;
  private final ClientRegistrationRepository repository;

  Providers(Environment environment) {
    registrations = registrations(environment);
    repository = new InMemoryClientRegistrationRepository(registrations);
  }

  /** The registrations of the providers that are on, in a stable order. */
  static Map<String, ClientRegistration> registrations(Environment environment) {
    var binder = Binder.get(environment);
    var on = new LinkedHashMap<String, ClientRegistration>();
    for (var name : NAMES) {
      var settings = binder.bind("semaphore.auth." + name, Settings.class);
      if (settings.isBound() && settings.get().on()) {
        on.put(name, register(name, settings.get()));
      }
    }
    return on;
  }

  private static ClientRegistration register(String name, Settings settings) {
    var builder =
        name.equals("github")
            ? github(name)
            : CommonOAuth2Provider.GOOGLE.getBuilder(name).scope("openid");
    builder
        .clientId(settings.clientId())
        .clientSecret(settings.clientSecret())
        .redirectUri("{baseUrl}" + CALLBACK + "/{registrationId}");
    replace(settings.authorizationUri(), builder::authorizationUri);
    replace(settings.tokenUri(), builder::tokenUri);
    replace(settings.userInfoUri(), builder::userInfoUri);
    replace(settings.jwkSetUri(), builder::jwkSetUri);
    replace(settings.issuerUri(), builder::issuerUri);
    return builder.build();
  }

  /**
   * Spring's built-in GitHub registration asks for {@code read:user}, and a builder cannot clear a
   * scope once set, so GitHub's three addresses are declared here: with no scope, a token reads the
   * public profile only.
   */
  private static ClientRegistration.Builder github(String name) {
    return ClientRegistration.withRegistrationId(name)
        .clientName("GitHub")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationUri("https://github.com/login/oauth/authorize")
        .tokenUri("https://github.com/login/oauth/access_token")
        .userInfoUri("https://api.github.com/user")
        .userNameAttributeName("id");
  }

  private static void replace(String value, Consumer<String> setter) {
    if (value != null && !value.isBlank()) {
      setter.accept(value);
    }
  }

  ClientRegistrationRepository repository() {
    return repository;
  }

  /** The names the front draws a button for. */
  public List<String> names() {
    return List.copyOf(registrations.keySet());
  }
}
