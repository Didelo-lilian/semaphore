package fr.didelo.semaphore.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import fr.didelo.semaphore.Postgres;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A connection that no longer answers must free the thread that waits on it, not hold it. */
@SpringBootTest(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=1")
class DatabaseHangsIT {

  private static final PostgreSQLContainer POSTGRES = Postgres.container();

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    Postgres.connect(registry, POSTGRES);
  }

  @Autowired DataSource pool;

  @Test
  void aStatementOnAFrozenPostgresFailsAfterTheSocketTimeout() throws SQLException {
    var docker = POSTGRES.getDockerClient();
    try (var connection = pool.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SELECT 1");
      docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
      try {
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              assertThatThrownBy(() -> statement.execute("SELECT 1"))
                  .isInstanceOf(SQLException.class);
            });
      } finally {
        docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
      }
    }
  }
}
