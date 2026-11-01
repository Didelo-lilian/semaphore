package fr.didelo.semaphore.db;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Without {@code spring.datasource.url} the application starts, as development and the e2e do. */
@SpringBootTest
class NoDatabaseTest {

  @Autowired ApplicationContext context;

  @Test
  void startsWithoutADataSourceNorMigrations() {
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
  }
}
