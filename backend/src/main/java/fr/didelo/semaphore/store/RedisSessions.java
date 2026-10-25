package fr.didelo.semaphore.store;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.RedisSessionRepository;

/**
 * Sessions in Valkey, one hash per session with a time to live, read by both replicas (ADR 0010).
 * The rooms keep their own Lettuce connection; this one is Spring Data's, which Spring Session is
 * built on. It is not Spring Boot's: nothing here adds a Redis health indicator, and nothing
 * connects before the first session is needed, so the backend starts before Valkey does.
 */
@Configuration
@WithRedis
class RedisSessions {

  @Bean
  LettuceConnectionFactory sessionConnectionFactory(
      @Value("${" + RedisConfigured.URL + "}") String url) {
    // Fail at once while Valkey is out of reach, as RedisLink does: a handshake must not wait on
    // it.
    var client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .clientOptions(
                ClientOptions.builder()
                    .protocolVersion(ProtocolVersion.RESP2)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(
                        SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                    .build())
            .build();
    return new LettuceConnectionFactory(
        LettuceConnectionFactory.createRedisConfiguration(url), client);
  }

  @Bean
  RedisSessionRepository sessions(
      RedisConnectionFactory connections,
      @Value("${semaphore.session.lifetime}") Duration lifetime) {
    var redis = new RedisTemplate<String, Object>();
    redis.setConnectionFactory(connections);
    redis.setKeySerializer(RedisSerializer.string());
    redis.setHashKeySerializer(RedisSerializer.string());
    redis.setDefaultSerializer(RedisSerializer.java(getClass().getClassLoader()));
    redis.afterPropertiesSet();
    var sessions = new RedisSessionRepository(redis);
    sessions.setDefaultMaxInactiveInterval(lifetime);
    sessions.setRedisKeyNamespace("session");
    return sessions;
  }
}
