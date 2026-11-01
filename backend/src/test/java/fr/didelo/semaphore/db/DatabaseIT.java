package fr.didelo.semaphore.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
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
    Postgres.connect(registry, Postgres.shared());
  }

  @Autowired Flyway flyway;
  @Autowired JdbcClient jdbc;
  @Autowired HikariDataSource pool;

  @Test
  void migratesAtStartAndQueries() {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(
            jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Long.class)
                .single())
        .isEqualTo(flyway.info().applied().length);
  }

  @Test
  void thePoolIsTheOneOfTheSettings() {
    assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
    assertThat(pool.getMinimumIdle()).isEqualTo(1);
    assertThat(pool.getConnectionTimeout()).isEqualTo(2_000);
    assertThat(pool.getDataSourceProperties()).containsEntry("socketTimeout", "10");
  }
}
