package dev.testgrid.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;

/**
 * Strict request parsing (contracts §1): unknown fields, duplicate keys, trailing content, numbers
 * for enums and "42"-style coercion are all rejected rather than guessed at.
 */
@Configuration(proxyBeanMethods = false)
class JsonConfig {

  @Bean
  JsonMapperBuilderCustomizer strictRequestParsing() {
    return builder ->
        builder
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }
}
