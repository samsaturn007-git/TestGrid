package dev.testgrid.api.run;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RunController {

  private final RunCreationService creation;

  public RunController(RunCreationService creation) {
    this.creation = creation;
  }

  /** 202: the run is queued, not executed. Idempotent replay (200 / 409) arrives in T3. */
  @PostMapping("/projects/{projectId}/runs")
  public ResponseEntity<RunResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateRunRequest request) {
    RunResponse run = creation.create(projectId, request);
    return ResponseEntity.accepted().location(URI.create(run.links().self())).body(run);
  }
}
