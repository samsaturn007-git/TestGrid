package dev.testgrid.api.internal;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contracts §7, served only on the internal port behind the service token ({@link
 * InternalAccessFilter}). Not part of the public OpenAPI spec.
 */
@RestController
@RequestMapping(
    path = "/internal/v1",
    produces = MediaType.APPLICATION_JSON_VALUE,
    consumes = MediaType.APPLICATION_JSON_VALUE)
public class WorkerController {

  public static final String REPLAY_HEADER = "Idempotent-Replay";

  private final AttemptService attempts;

  public WorkerController(AttemptService attempts) {
    this.attempts = attempts;
  }

  @PostMapping("/claims/next")
  public ResponseEntity<WorkerApi.ClaimResponse> claimNext(
      @Valid @RequestBody WorkerApi.ClaimRequest request) {
    return attempts
        .claimNext(request)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/attempts/{attemptId}/start")
  public WorkerApi.StartResponse start(
      @PathVariable UUID attemptId, @Valid @RequestBody WorkerApi.LeaseRequest request) {
    return attempts.start(attemptId, request.leaseToken());
  }

  @PostMapping("/attempts/{attemptId}/heartbeat")
  public WorkerApi.HeartbeatResponse heartbeat(
      @PathVariable UUID attemptId, @Valid @RequestBody WorkerApi.LeaseRequest request) {
    return attempts.heartbeat(attemptId, request.leaseToken());
  }

  @PostMapping("/attempts/{attemptId}/complete")
  public ResponseEntity<WorkerApi.CompletionResponse> complete(
      @PathVariable UUID attemptId, @Valid @RequestBody WorkerApi.CompletionRequest request) {
    AttemptService.Completion completion = attempts.complete(attemptId, request);
    var ok = ResponseEntity.ok();
    if (completion.replay()) {
      ok = ok.header(REPLAY_HEADER, "true");
    }
    return ok.body(completion.response());
  }
}
