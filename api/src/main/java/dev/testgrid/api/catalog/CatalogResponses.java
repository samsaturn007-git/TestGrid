package dev.testgrid.api.catalog;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.FixtureMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Response shapes for the read-only catalog (PRD-02 FR-3). */
public final class CatalogResponses {

  private CatalogResponses() {}

  public record Project(UUID id, String slug, String name, Links links) {}

  public record Suite(
      UUID id,
      UUID projectId,
      String suiteKey,
      String revision,
      String displayName,
      boolean enabled,
      boolean current,
      int timeoutMs,
      int playwrightRetries,
      List<Engine> allowedEngines,
      List<FixtureMode> fixtureModes,
      int caseCount,
      Links links) {}

  public record SuiteDetail(Suite suite, List<Case> cases) {}

  @Schema(name = "TestCase")
  public record Case(
      String caseKey,
      String title,
      String tag,
      boolean critical,
      @ApiNullable Integer expectedDurationMs) {}

  @Schema(name = "ResourceLinks")
  public record Links(String self) {}
}
