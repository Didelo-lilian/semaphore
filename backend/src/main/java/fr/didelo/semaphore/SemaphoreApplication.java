package fr.didelo.semaphore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

// The DataSource is defined by fr.didelo.semaphore.db.Database, only when there is a database.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class SemaphoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(SemaphoreApplication.class, args);
  }
}
