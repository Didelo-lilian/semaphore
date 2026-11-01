package fr.didelo.semaphore.db;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connection to Postgres, when {@code spring.datasource.url} says where it is. Spring's own
 * setup is off ({@code SemaphoreApplication}) because it refuses to start without a URL, and the
 * game plays without a database; Flyway and the JDBC client follow from the {@link DataSource}
 * defined here.
 */
@Configuration
@WithDatabase
@EnableConfigurationProperties(DataSourceProperties.class)
class Database {

  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }
}
