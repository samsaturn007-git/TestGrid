package dev.testgrid.api.run;

import dev.testgrid.api.common.Page;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemBody;
import dev.testgrid.api.problem.ProblemType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
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
  private final RunCancelService cancels;

  public RunController(
      RunCreationService creation, RunQueryService queries, RunCancelService cancels) {
    this.creation = creation;
    this.queries = queries;
    this.cancels = cancels;
  }

  private static final Set<String> LIST_PARAMS =
      Set.of("limit", "cursor", "projectId", "status", "suiteKey", "branch", "trigger");

  @GetMapping("/runs")
  @Operation(
      operationId = "listRuns",
      summary = "List runs, newest first",
      description =
          "Keyset-paginated: pass nextCursor back as cursor, with the same filters. Null on the"
              + " last page. Repeat status or trigger to match any of several values; different"
              + " filters must all match.")
  @ApiResponse(responseCode = "200", description = "A page of runs")
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed (limit, cursor or a filter)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (projectId outside the caller's scope)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public Page<RunSummary> list(
      @Parameter(description = "1-100, default 20") @RequestParam(required = false) Integer limit,
      @Parameter(description = "Opaque; from a previous page's nextCursor")
          @RequestParam(required = false)
          String cursor,
      @Parameter(description = "Defaults to the caller's project") @RequestParam(required = false)
          UUID projectId,
      @Parameter(description = "Aggregate run status; repeat for several")
          @RequestParam(name = "status", required = false)
          List<RunStatus> status,
      @Parameter(description = "Exact suite key, any revision") @RequestParam(required = false)
          String suiteKey,
      @Parameter(description = "Exact branch name") @RequestParam(required = false) String branch,
      @Parameter(description = "Repeat for several")
          @RequestParam(name = "trigger", required = false)
          List<Trigger> trigger,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all) {
    // A misspelt filter (?suite=) must not quietly return the unfiltered list.
    List<FieldError> unknown =
        all.keySet().stream()
            .filter(name -> !LIST_PARAMS.contains(name))
            .sorted()
            .map(name -> new FieldError(name, "unknown query parameter"))
            .toList();
    if (!unknown.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the run list query has unknown parameters", unknown);
    }
    return queries.list(limit, cursor, projectId, status, suiteKey, branch, trigger);
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

  /**
   * PRD-04 FR-8. 202 when the cancel was accepted: QUEUED targets are CANCELLED at once and active
   * attempts stop on their next heartbeat. 200 with the run unchanged when it was already terminal.
   */
  @PostMapping("/runs/{runId}/cancel")
  @Operation(
      operationId = "cancelRun",
      summary = "Cancel a run",
      description =
          "QUEUED targets end CANCELLED immediately; active attempts are told on their next"
              + " heartbeat and finish CANCELLED unless their completion commits first. A run"
              + " that is already terminal is returned unchanged with 200.")
  @ApiResponse(
      responseCode = "202",
      description = "Cancel accepted",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunResponse.class)))
  @ApiResponse(
      responseCode = "200",
      description = "Already terminal: nothing changed",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ResponseEntity<RunResponse> cancel(@PathVariable UUID runId) {
    RunCancelService.Result result = cancels.cancel(runId);
    return result.accepted()
        ? ResponseEntity.accepted().body(result.run())
        : ResponseEntity.ok(result.run());
  }
}
