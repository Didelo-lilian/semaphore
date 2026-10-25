package fr.didelo.semaphore;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres for every integration test of a run. */
public final class Postgres {

  private static final PostgreSQLContainer CONTAINER = container();

  private Postgres() {}

  static {
    CONTAINER.start();
  }

  /** A Postgres of its own, not started. */
  public static PostgreSQLContainer container() {
    return new PostgreSQLContainer("postgres:17-alpine");
  }

  public static PostgreSQLContainer shared() {
    return CONTAINER;
  }
}
