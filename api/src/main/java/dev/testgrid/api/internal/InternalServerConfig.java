package dev.testgrid.api.internal;

import java.util.Optional;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FR-2: the worker API listens on its own port, a second Tomcat connector. Compose puts that port
 * on the internal network only and never publishes it; {@link InternalAccessFilter} keeps each port
 * to its own paths.
 */
@Configuration(proxyBeanMethods = false)
class InternalServerConfig {

  /** Empty when no worker token is configured: the internal port is then never opened. */
  @Bean
  InternalConnector internalConnector(InternalApiProperties properties) {
    if (!properties.enabled()) {
      return new InternalConnector(Optional.empty());
    }
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(properties.port());
    return new InternalConnector(Optional.of(connector));
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalPort(
      InternalConnector internal) {
    return factory -> internal.connector().ifPresent(factory::addAdditionalConnectors);
  }

  /** {@code localPort()} is the bound port, so tests can use port 0. */
  record InternalConnector(Optional<Connector> connector) {
    int localPort() {
      return connector.map(Connector::getLocalPort).orElse(-1);
    }
  }
}
