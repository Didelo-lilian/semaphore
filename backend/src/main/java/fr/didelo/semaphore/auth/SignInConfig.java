package fr.didelo.semaphore.auth;

import fr.didelo.semaphore.history.GameHistory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Security's OAuth2 login for the two providers (ADR 0011), on a chain of its own that only
 * the login and callback routes of the providers that are on reach; every other route, the unknown
 * providers' included, stays with the chain of ADR 0010. The CSRF protection of ADR 0010 stays on
 * here too: a {@code GET}, which is all a login and its callback are, never needs the token, and
 * anything else sent to these routes is refused without it. The authorization-code flow with {@code
 * state} and PKCE runs on the backend, and nothing the provider returns is kept: the token goes
 * unstored, and the session is written by {@link LoginOutcome}. The calls to the providers time out
 * at 3 seconds to connect and 5 to read, since Spring's default has no limit and a provider that
 * hangs would hold the threads of a small pool.
 */
@Configuration
@WithSignIn
class SignInConfig {

  private static final JdkClientHttpRequestFactory PROVIDER_CALLS =
      new JdkClientHttpRequestFactory(
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());

  static {
    PROVIDER_CALLS.setReadTimeout(Duration.ofSeconds(5));
  }

  @Bean
  Providers providers(Environment environment) {
    return Providers.of(environment);
  }

  /** An account session does not outlive the cookie (ADR 0011): the same property gives both. */
  @Bean
  Accounts accounts(
      JdbcClient jdbc,
      GameHistory history,
      TransactionTemplate transaction,
      @Value("${semaphore.session.cookie-max-age}") Duration sessionLife) {
    return new Accounts(jdbc, history, transaction, sessionLife);
  }

  @Bean
  @Order(1)
  SecurityFilterChain signIn(
      HttpSecurity http,
      Providers providers,
      Accounts accounts,
      CookieLoginRequests requests,
      LoginOutcome outcome,
      CsrfTokenRepository tokens,
      JwtDecoderFactory<ClientRegistration> idTokens) {
    var repository = providers.repository();
    var authorize = new DefaultOAuth2AuthorizationRequestResolver(repository, Providers.LOGIN);
    authorize.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    var token = new RestClientAuthorizationCodeTokenResponseClient();
    token.setRestClient(
        RestClient.builder()
            .requestFactory(PROVIDER_CALLS)
            .configureMessageConverters(
                converters ->
                    converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    var userInfo = new RestTemplate(PROVIDER_CALLS);
    userInfo.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    var github = new DefaultOAuth2UserService();
    github.setRestOperations(userInfo);
    // The ID token says who the player is: nothing is asked of the user-info endpoint.
    var google = new OidcUserService();
    google.setRetrieveUserInfo(request -> false);
    var routes =
        providers.names().stream()
            .flatMap(
                name -> Stream.of(Providers.LOGIN + "/" + name, Providers.CALLBACK + "/" + name))
            .toArray(String[]::new);
    return http.securityMatcher(routes)
        .authorizeHttpRequests(requestsOf -> requestsOf.anyRequest().permitAll())
        .sessionManagement(AbstractHttpConfigurer::disable)
        .securityContext(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .anonymous(AbstractHttpConfigurer::disable)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(tokens)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .oauth2Login(
            login -> {
              login.withObjectPostProcessor(new RedirectFailures(outcome));
              login
                  .clientRegistrationRepository(repository)
                  .authorizedClientRepository(new NoAuthorizedClients())
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint
                              .authorizationRequestResolver(authorize)
                              .authorizationRequestRepository(requests))
                  .redirectionEndpoint(endpoint -> endpoint.baseUri(Providers.CALLBACK + "/*"))
                  .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(token))
                  .userInfoEndpoint(
                      endpoint -> endpoint.userService(github).oidcUserService(google))
                  .successHandler(outcome)
                  .failureHandler(outcome);
            })
        .addFilterBefore(
            new PseudoFirst(accounts, providers), OAuth2AuthorizationRequestRedirectFilter.class)
        .addFilterBefore(new HomeOnFailure(), PseudoFirst.class)
        .build();
  }

  /**
   * What Spring's default decoder does, with the timeouts: the signature of the ID token is checked
   * against the provider's keys, which are fetched once and cached by the decoder.
   */
  @Bean
  JwtDecoderFactory<ClientRegistration> idTokens() {
    var keys = new RestTemplate(PROVIDER_CALLS);
    var decoders = new ConcurrentHashMap<String, JwtDecoder>();
    return registration ->
        decoders.computeIfAbsent(
            registration.getRegistrationId(),
            id -> {
              var decoder =
                  NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                      .jwsAlgorithm(SignatureAlgorithm.RS256)
                      .restOperations(keys)
                      .build();
              decoder.setJwtValidator(
                  new DelegatingOAuth2TokenValidator<>(
                      new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
              decoder.setClaimSetConverter(
                  new ClaimTypeConverter(
                      OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
              return decoder;
            });
  }

  /**
   * The filter that starts a login answers a bare 500 to whatever goes wrong, when Valkey cannot
   * keep the login for one; it sends the player home with the code of every failure instead.
   */
  @RequiredArgsConstructor
  private static final class RedirectFailures
      implements ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter> {

    private final AuthenticationFailureHandler failures;

    @Override
    public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
      filter.setAuthenticationFailureHandler(failures);
      return filter;
    }
  }

  /** Spring's default keeps the provider's token in memory for as long as the process runs. */
  private static final class NoAuthorizedClients implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String registrationId, Authentication principal, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient client,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(
        String registrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}
  }
}
