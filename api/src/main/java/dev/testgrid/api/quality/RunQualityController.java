package dev.testgrid.api.quality;

import dev.testgrid.api.problem.ProblemBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 {@code GET /runs/{runId}/quality} (PRD-08 FR-2). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "quality")
public class RunQualityController {

  private final RunQualityService quality;

  public RunQualityController(RunQualityService quality) {
    this.quality = quality;
  }

  @GetMapping(path = "/runs/{runId}/quality", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getRunQuality",
      summary = "The run's quality evaluation and rule report",
      description =
          "Made once, when the run finishes, with the policy active then; a finished run without"
              + " one yet is evaluated by this request. Before the run finishes outcome is null."
              + " PASS maps to CLI exit 0, FAIL to 1, INDETERMINATE to 2 (contracts §2.4).")
  @ApiResponse(
      responseCode = "200",
      description = "The evaluation, or an empty one while the run is unfinished",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunQualityView.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemBody.class)))
  public RunQualityView get(@PathVariable UUID runId) {
    return quality.quality(runId);
  }
}
