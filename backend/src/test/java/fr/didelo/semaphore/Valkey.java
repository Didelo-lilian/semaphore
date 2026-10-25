package fr.didelo.semaphore;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;

/** One Valkey for every integration test of a run, set up like the server's: password, 64 MB. */
public final class Valkey {

  private static final String PASSWORD = "secret";
  private static final GenericContainer<?> CONTAINER = container(null);

  private Valkey() {}

  static {
    CONTAINER.start();
  }

  /** A Valkey of its own, not started, that listens on {@code port} of this machine. */
  public static GenericContainer<?> container(Integer port) {
    var container =
        new GenericContainer<>("valkey/valkey:9.1.2-alpine")
            .withCommand(
                "valkey-server",
                "--requirepass",
                PASSWORD,
                "--maxmemory",
                "64mb",
                "--maxmemory-policy",
                "noeviction")
            .withExposedPorts(6379);
    if (port != null) {
      container.withCreateContainerCmdModifier(
          command ->
              command
                  .getHostConfig()
                  .withPortBindings(
                      new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(6379))));
    }
    return container;
  }

  public static String url() {
    return url(CONTAINER);
  }

  public static String url(GenericContainer<?> container) {
    return "redis://:%s@%s:%d"
        .formatted(PASSWORD, container.getHost(), container.getMappedPort(6379));
  }

  public static String url(int port) {
    return "redis://:%s@%s:%d".formatted(PASSWORD, CONTAINER.getHost(), port);
  }
}
