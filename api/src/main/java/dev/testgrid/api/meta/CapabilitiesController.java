package dev.testgrid.api.meta;

import dev.testgrid.api.config.TestgridProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment accepts, so the dashboard can hide controls the API would refuse (PRD-05
 * FR-2: fixture mode in the local profile only). It answers a boolean rather than the profile name,
 * so it says no more about the deployment than the UI needs.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "meta")
public class CapabilitiesController {

  /** {@code fixturesAllowed} is true exactly when {@code TESTGRID_PROFILE=local}. */
  public record Capabilities(boolean fixturesAllowed) {}

  private final TestgridProperties properties;

  public CapabilitiesController(TestgridProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/capabilities")
  @Operation(operationId = "getCapabilities", summary = "What this deployment accepts")
  @ApiResponse(responseCode = "200", description = "Capabilities")
  public Capabilities capabilities() {
    return new Capabilities(properties.isLocal());
  }
}
