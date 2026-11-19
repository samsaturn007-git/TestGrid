package dev.testgrid.api.manifest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class ManifestConfig {

  @Bean
  ManifestLoader manifestLoader() {
    return new ManifestLoader();
  }

  @Bean
  ManifestSeeder manifestSeeder(
      ManifestLoader loader,
      ManifestProperties properties,
      JdbcClient jdbc,
      TransactionTemplate tx) {
    return new ManifestSeeder(loader, properties, jdbc, tx);
  }
}
