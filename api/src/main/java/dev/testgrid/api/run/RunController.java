package dev.testgrid.api.run;

import dev.testgrid.api.common.Page;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RunController {

  private final RunCreationService creation;
  private final RunQueryService queries;

  public RunController(RunCreationService creation, RunQueryService queries) {
    this.creation = creation;
    this.queries = queries;
  }

  /** Newest first, keyset-paginated: pass {@code nextCursor} back as {@code cursor}. */
  @GetMapping("/runs")
  public Page<RunSummary> list(
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return queries.list(limit, cursor);
  }

  @GetMapping("/runs/{runId}")
  public RunResponse get(@PathVariable UUID runId) {
    return queries.get(runId);
  }

  public static final String REPLAY_HEADER = "Idempotent-Replay";

  /**
   * 202 for a new run (queued, not executed); 200 with {@code Idempotent-Replay: true} when the
   * same key and request were seen before; 409 when the key was used with a different request.
   */
  @PostMapping("/projects/{projectId}/runs")
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
