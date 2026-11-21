package dev.testgrid.api.worker;

import dev.testgrid.api.dispatch.DispatchRepository;
import dev.testgrid.api.dispatch.SchedulerSettingsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PRD-04 FR-7: {@code GET /api/v1/workers}, the registry and slot usage (contracts §4). */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "workers")
public class WorkersController {

  private final WorkerRegistry registry;
  private final DispatchRepository dispatch;
  private final SchedulerSettingsRepository settings;

  public WorkersController(
      WorkerRegistry registry, DispatchRepository dispatch, SchedulerSettingsRepository settings) {
    this.registry = registry;
    this.dispatch = dispatch;
    this.settings = settings;
  }

  /**
   * {@code activeSessions} counts every CLAIMED/RUNNING attempt; {@code dispatchedUnclaimed} the
   * targets handed to the broker that no worker has claimed yet. Together they never exceed {@code
   * globalMaxSessions} (invariant I5).
   */
  public record WorkersResponse(
      List<WorkerView> items, int globalMaxSessions, int activeSessions, int dispatchedUnclaimed) {}

  @GetMapping("/workers")
  @Operation(
      operationId = "listWorkers",
      summary = "Worker registry and slot usage",
      description = "Live workers (ACTIVE, DRAINING) and those LOST in the last hour.")
  @ApiResponse(responseCode = "200", description = "The registry")
  @Transactional(readOnly = true)
  public WorkersResponse list() {
    var capacity = dispatch.capacity();
    return new WorkersResponse(
        registry.list(),
        settings.current().globalMaxSessions(),
        capacity.active(),
        capacity.dispatchedUnclaimed());
  }
}
