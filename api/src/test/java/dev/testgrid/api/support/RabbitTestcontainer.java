package dev.testgrid.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Real RabbitMQ for the PRD-04 dispatch tests (quorum queues need a real broker). */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainer {

  // Same image tag as infra/compose/compose.yaml (docs/VERSIONS.md).
  public static final String IMAGE = "rabbitmq:4.4.1-management";

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbit() {
    return new RabbitMQContainer(IMAGE);
  }
}
