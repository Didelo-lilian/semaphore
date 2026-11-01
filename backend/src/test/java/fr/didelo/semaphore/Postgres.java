package fr.didelo.semaphore;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres for every integration test of a run, on the major version that infra deploys. */
public final class Postgres {

  private static final PostgreSQLContainer CONTAINER = container();

  private Postgres() {}

  static {
    CONTAINER.start();
  }

  /** A Postgres of its own, not started. */
  public static PostgreSQLContainer container() {
    return new PostgreSQLContainer("postgres:18-alpine");
  }

  public static PostgreSQLContainer shared() {
    return CONTAINER;
  }

  /** Points the backend of a test at a Postgres that is started. */
  public static void connect(DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
}
