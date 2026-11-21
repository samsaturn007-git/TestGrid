package dev.testgrid.api.run;

import dev.testgrid.api.common.Page;
import dev.testgrid.api.problem.ProblemBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "runs")
public class RunController {

  public static final String REPLAY_HEADER = "Idempotent-Replay";
  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private final RunCreationService creation;
  private final RunQueryService queries;

  public RunController(RunCreationService creation, RunQueryService queries) {
    this.creation = creation;
    this.queries = queries;
  }

  @GetMapping("/runs")
  @Operation(
      operationId = "listRuns",
      summary = "List runs, newest first",
      description = "Keyset-paginated: pass nextCursor back as cursor. Null on the last page.")
  @ApiResponse(responseCode = "200", description = "A page of runs")
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed (limit or cursor)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public Page<RunSummary> list(
      @Parameter(description = "1-100, default 20") @RequestParam(required = false) Integer limit,
      @Parameter(description = "Opaque; from a previous page's nextCursor")
          @RequestParam(required = false)
          String cursor) {
    return queries.list(limit, cursor);
  }

  @GetMapping("/runs/{runId}")
  @Operation(operationId = "getRun", summary = "A run with its targets and aggregate status")
  @ApiResponse(responseCode = "200", description = "The run")
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public RunResponse get(@PathVariable UUID runId) {
    return queries.get(runId);
  }

  @GetMapping("/runs/{runId}/results")
  @Operation(
      operationId = "getRunResults",
      summary = "Every attempt per target with its case results, newest attempt first")
  @ApiResponse(responseCode = "200", description = "The results")
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public RunResults results(@PathVariable UUID runId) {
    return queries.results(runId);
  }

  /**
   * 202 for a new run (queued, not executed); 200 with {@code Idempotent-Replay: true} when the
   * same key and request were seen before; 409 when the key was used with a different request.
   */
  @PostMapping(path = "/projects/{projectId}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "createRun", summary = "Create a run (idempotent by idempotencyKey)")
  @ApiResponse(
      responseCode = "202",
      description = "New run queued",
      headers = @Header(name = "Location", description = "/api/v1/runs/{runId}"),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunResponse.class)))
  @ApiResponse(
      responseCode = "200",
      description = "Replay: this key was already used with the same request",
      headers = {
        @Header(name = "Location", description = "/api/v1/runs/{runId}"),
        @Header(name = REPLAY_HEADER, description = "Always \"true\"")
      },
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (project)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "409",
      description = "idempotency-conflict",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "422",
      description = "unknown-suite, engine-not-allowed or fixture-not-allowed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ResponseEntity<RunResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateRunRequest request) {
    RunCreationService.Result result = creation.create(projectId, request);
    URI location = URI.create(result.run().links().self());
    if (result.replay()) {
      return ResponseEntity.ok()
          .header(REPLAY_HEADER, "true")
          .location(location)
          .body(result.run());
    }
    return ResponseEntity.accepted().location(location).body(result.run());
  }
}
