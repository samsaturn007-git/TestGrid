package dev.testgrid.api.failure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Loads {@code failure-rules.yaml} at startup; an invalid table stops the API (FR-2). */
@Configuration(proxyBeanMethods = false)
class FailureRulesConfig {

  static final String RESOURCE = "failure-rules.yaml";

  @Bean
  FailureClassifier failureClassifier() {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      return new FailureClassifier(FailureRules.load(in));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + RESOURCE, e);
    }
  }
}
