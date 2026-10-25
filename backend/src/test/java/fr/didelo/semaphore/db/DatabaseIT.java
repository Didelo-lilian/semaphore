package fr.didelo.semaphore.db;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Postgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class DatabaseIT {

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    var postgres = Postgres.shared();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Flyway flyway;
  @Autowired JdbcClient jdbc;

  @Test
  void migratesAtStartAndQueries() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    assertThat(jdbc.sql("SELECT count(*) FROM game").query(Long.class).single()).isZero();
  }
}
