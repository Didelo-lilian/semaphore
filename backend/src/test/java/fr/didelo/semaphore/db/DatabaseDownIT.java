package fr.didelo.semaphore.db;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Postgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The game goes on, and stays in the load balancer, while Postgres is down (ADR 0008). */
@SpringBootTest
class DatabaseDownIT {

  private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
      Postgres.container();

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired HealthEndpoint health;

  @Test
  void staysHealthyWhenPostgresStops() {
    POSTGRES.stop();

    var descriptor = health.health();
    assertThat(descriptor.getStatus()).isEqualTo(Status.UP);
    assertThat(((CompositeHealthDescriptor) descriptor).getComponents()).doesNotContainKey("db");
  }
}
