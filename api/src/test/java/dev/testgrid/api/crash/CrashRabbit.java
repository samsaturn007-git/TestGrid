package dev.testgrid.api.crash;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.testgrid.api.support.RabbitTestcontainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * RabbitMQ for the crash tests with its AMQP port bound to a fixed loopback port, so a {@code
 * docker restart} of the broker (AC-5) comes back on the same address and both the API and the
 * simulated workers can reconnect. A random mapping would move on restart.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CrashRabbit {

  @Bean
  @ServiceConnection
  RabbitMQContainer crashRabbit() {
    int port = freePort();
    return new RabbitMQContainer(RabbitTestcontainer.IMAGE)
        .withCreateContainerCmdModifier(cmd -> {
          // Pin 5672; keep a random loopback binding for every other exposed port (the
          // container's readiness check waits for all of them).
          List<PortBinding> bindings = new ArrayList<>();
          for (ExposedPort exposed : cmd.getExposedPorts()) {
            bindings.add(
                new PortBinding(
                    exposed.getPort() == 5672
                        ? Ports.Binding.bindIpAndPort("127.0.0.1", port)
                        : Ports.Binding.bindIp("127.0.0.1"),
                    exposed));
          }
          cmd.getHostConfig().withPortBindings(bindings);
        });
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
