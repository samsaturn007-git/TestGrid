package dev.testgrid.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated spec is committed as api/openapi.yaml (PRD-02 FR-9), so it must not depend on where
 * it was generated: a relative server URL instead of the detected host and port.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  OpenAPI testgridOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("TestGrid API")
                .version("v1")
                .description(
                    "Public API (/api/v1). Vocabulary: docs/API_AND_DATA_CONTRACTS.md. Errors are"
                        + " RFC 9457 Problem Details."))
        .servers(List.of(new Server().url("/")));
  }
}
