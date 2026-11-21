package dev.testgrid.api.run;

import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.dispatch.DispatchSignal;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.internal.AttemptRepository;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-04 FR-8, contracts §2.2. One transaction:
 *
 * <ul>
 *   <li>A run that is already terminal is answered {@code 200} unchanged. The request is still
 *       recorded ({@code RUN_CANCEL_REQUESTED} with {@code accepted: false}), so a cancel that lost
 *       a race with the last completion leaves an audit trail (AC-7).
 *   <li>Otherwise {@code cancel_requested_at} is set, every {@code QUEUED} target ends {@code
 *       CANCELLED} at once, and active attempts learn of the request on their next heartbeat; the
 *       worker stops Playwright and completes with {@code CANCELLED}. A completion that commits
 *       before the worker sees the request keeps its real result. Either way the target gets
 *       exactly one terminal state: whichever transaction commits first wins, and the other is
 *       refused or finds nothing left to change.
 * </ul>
 */
@Service
public class RunCancelService {

  private static final Logger log = LoggerFactory.getLogger(RunCancelService.class);

  private final RunCancelRepository cancels;
  private final RunRepository runs;
  private final AttemptRepository events;
  private final CallerScope scope;
  private final DispatchSignal signal;
  private final JsonMapper mapper;

  public RunCancelService(
      RunCancelRepository cancels,
      RunRepository runs,
      AttemptRepository events,
      CallerScope scope,
      DispatchSignal signal,
      JsonMapper mapper) {
    this.cancels = cancels;
    this.runs = runs;
    this.events = events;
    this.scope = scope;
    this.signal = signal;
    this.mapper = mapper;
  }

  /** {@code accepted} is false when the run was already terminal and nothing changed. */
  public record Result(RunResponse run, boolean accepted) {}

  @Transactional
  public Result cancel(UUID runId) {
    UUID projectId = scope.projectId();
    List<RunCancelRepository.LockedTarget> targets = cancels.lockTargets(projectId, runId);
    RunCancelRepository.LockedRun run =
        cancels
            .lockRun(projectId, runId)
            .orElseThrow(() ->
                new ApiProblemException(ProblemType.NOT_FOUND, "run " + runId + " not found"));
    try (var ctx = LogContext.open().put("runId", runId)) {
      if (run.status().isTerminal()) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", false);
        payload.put("runStatus", run.status().name());
        event(runId, null, EventType.RUN_CANCEL_REQUESTED, payload);
        log.info("cancel request for a run that is already {}: nothing changed", run.status());
        return new Result(runs.findRun(projectId, runId).orElseThrow(), false);
      }

      cancels.markCancelRequested(runId);
      List<UUID> cancelled = cancels.cancelQueuedTargets(runId);
      long active =
          targets.stream()
              .filter(t -> t.status() == TargetStatus.CLAIMED || t.status() == TargetStatus.RUNNING)
              .count();
      Map<String, Object> requested = new LinkedHashMap<>();
      requested.put("accepted", true);
      requested.put("alreadyRequested", run.cancelRequested());
      requested.put("cancelledTargets", cancelled.size());
      requested.put("activeTargets", active);
      event(runId, null, EventType.RUN_CANCEL_REQUESTED, requested);
      for (UUID target : cancelled) {
        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("status", TargetStatus.CANCELLED.name());
        finished.put("attemptNo", null);
        finished.put("errorClass", "CANCELLED");
        event(runId, target, EventType.TARGET_FINISHED, finished);
      }
      var change = events.recomputeRun(runId);
      if (change.finishedNow()) {
        event(runId, null, EventType.RUN_FINISHED, Map.of("status", change.status().name()));
      }
      if (!cancelled.isEmpty()) {
        // Dispatched-but-unclaimed targets held cap slots; they are free now.
        signal.dispatchAfterCommit();
      }
      log.info(
          "cancel requested: {} queued target(s) cancelled, {} active target(s) notified on their"
              + " next heartbeat, run {}",
          cancelled.size(),
          active,
          change.status());
      return new Result(runs.findRun(projectId, runId).orElseThrow(), true);
    }
  }

  private void event(UUID runId, UUID targetId, EventType type, Map<String, ?> payload) {
    events.insertEvent(runId, targetId, null, type, mapper.writeValueAsString(payload));
  }
}
