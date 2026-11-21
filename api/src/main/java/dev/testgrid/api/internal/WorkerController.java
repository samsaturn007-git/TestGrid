package dev.testgrid.api.internal;

import dev.testgrid.api.worker.WorkerRegistry;
import dev.testgrid.api.worker.WorkerView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
  private final WorkerRegistry registry;
  private final InternalApiProperties properties;

  public WorkerController(
      AttemptService attempts, WorkerRegistry registry, InternalApiProperties properties) {
    this.attempts = attempts;
    this.registry = registry;
    this.properties = properties;
  }

  /** PRD-04 FR-7: a new registry row; a live row with the same name becomes LOST. */
  @PostMapping("/workers/register")
  public ResponseEntity<WorkerApi.RegisterResponse> register(
      @Valid @RequestBody WorkerApi.RegisterRequest request) {
    WorkerView worker =
        registry.register(
            request.name(),
            request.version(),
            request.executor(),
            request.engines().stream().distinct().toList(),
            request.slots());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new WorkerApi.RegisterResponse(
                worker.id(), worker.name(), worker.state(), properties.heartbeatIntervalSeconds()));
  }

  /** {@code 410 lease-expired} once the row is LOST: the worker registers again. */
  @PostMapping("/workers/{workerId}/heartbeat")
  public WorkerApi.WorkerStateResponse workerHeartbeat(@PathVariable UUID workerId) {
    return new WorkerApi.WorkerStateResponse(workerId, registry.heartbeat(workerId));
  }

  /** SIGTERM: the worker stops taking messages and finishes what it runs. */
  @PostMapping("/workers/{workerId}/drain")
  public WorkerApi.WorkerStateResponse drain(@PathVariable UUID workerId) {
    return new WorkerApi.WorkerStateResponse(workerId, registry.drain(workerId).state());
  }

  /**
   * PRD-04 FR-5. The PRD-03 {@code POST /claims/next} is gone (CONFLICT_REGISTER N11): every claim
   * now names a dispatched target and its generation, so none can skip the dispatcher's cap.
   */
  @PostMapping("/targets/{targetId}/claim")
  public WorkerApi.ClaimResponse claim(
      @PathVariable UUID targetId, @Valid @RequestBody WorkerApi.ClaimRequest request) {
    return attempts.claim(targetId, request);
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
