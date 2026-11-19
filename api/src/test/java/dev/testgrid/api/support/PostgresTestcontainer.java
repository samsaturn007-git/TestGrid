package dev.testgrid.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL for every test that loads the full context (ADR-001: no H2 substitutes). */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

  // Same image tag as infra/compose/compose.yaml (docs/VERSIONS.md).
  public static final String IMAGE = "postgres:18.7-bookworm";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(IMAGE);
  }
}
