package dev.testgrid.api.worker;

import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.dispatch.SchedulerSettingsRepository;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-04 FR-7. Workers register on start, heartbeat while alive, and drain on {@code SIGTERM}. A
 * live worker not heard from for 3 × the lease TTL becomes LOST. The registry is for operators
 * ({@code GET /api/v1/workers}); claims don't depend on it, the lease protocol already fences a
 * dead worker.
 */
@Service
public class WorkerRegistry {

  private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

  /** FR-7: LOST after 3 × TTL without a heartbeat. */
  static final int LOST_AFTER_TTLS = 3;

  private final WorkerRepository workers;
  private final SchedulerSettingsRepository settings;

  public WorkerRegistry(WorkerRepository workers, SchedulerSettingsRepository settings) {
    this.workers = workers;
    this.settings = settings;
  }

  @Transactional
  public WorkerView register(
      String name, String version, Executor executor, List<Engine> engines, int slots) {
    int replaced = workers.markPreviousLost(name);
    WorkerView worker = workers.insert(name, version, executor, List.copyOf(engines), slots);
    try (var ctx = LogContext.open().put("workerId", name)) {
      log.info(
          "worker registered id={} version={} engines={} slots={} replacedLiveRows={}",
          worker.id(),
          version,
          engines.stream().map(Engine::wireName).toList(),
          slots,
          replaced);
    }
    return worker;
  }

  @Transactional
  public WorkerState heartbeat(UUID id) {
    return workers.touch(id).map(WorkerState::valueOf).orElseThrow(() -> gone(id));
  }

  @Transactional
  public WorkerView drain(UUID id) {
    workers.drain(id).orElseThrow(() -> gone(id));
    WorkerView worker = workers.find(id).orElseThrow();
    try (var ctx = LogContext.open().put("workerId", worker.name())) {
      log.info("worker draining id={} activeAttempts={}", id, worker.activeAttempts());
    }
    return worker;
  }

  /** Reaper pass: live workers silent for 3 × TTL become LOST. */
  @Transactional
  public List<String> sweep() {
    int silence = LOST_AFTER_TTLS * settings.current().leaseTtlSeconds();
    List<String> lost = workers.markSilentLost(silence);
    for (String name : lost) {
      try (var ctx = LogContext.open().put("workerId", name)) {
        log.warn("worker LOST: no heartbeat for {} s", silence);
      }
    }
    return lost;
  }

  @Transactional(readOnly = true)
  public List<WorkerView> list() {
    return workers.list(200);
  }

  /** Unknown, or LOST: either way the worker has to register again. */
  private ApiProblemException gone(UUID id) {
    if (workers.state(id).isEmpty()) {
      return new ApiProblemException(ProblemType.NOT_FOUND, "worker " + id + " not found");
    }
    return new ApiProblemException(
        ProblemType.LEASE_EXPIRED, "worker " + id + " was marked LOST; register again");
  }
}
