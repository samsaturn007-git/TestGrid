package dev.testgrid.api.crash;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.testgrid.api.support.RabbitTestcontainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * RabbitMQ for the crash tests with its AMQP port bound to a fixed loopback port, so a {@code
 * docker restart} of the broker (AC-5) comes back on the same address and both the API and the
 * simulated workers can reconnect. A random mapping would move on restart.
 *
 * <p>Only AMQP is exposed: the crash tests use no management or TLS port, and Docker's random
 * bindings for those collided with a port already in use on a GitHub runner ("address already in
 * use" for 15671). The fixed port is chosen inside the create step and the start is tried up to 3
 * times, so a port taken between choosing and binding it gets a fresh one on the next try.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CrashRabbit {

  @Bean
  @ServiceConnection
  RabbitMQContainer crashRabbit() {
    return new RabbitMQContainer(RabbitTestcontainer.IMAGE)
        .withExposedPorts(5672)
        .withStartupAttempts(3)
        .withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig()
                .withPortBindings(
                    new PortBinding(
                        Ports.Binding.bindIpAndPort("127.0.0.1", freePort()),
                        new ExposedPort(5672))));
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
