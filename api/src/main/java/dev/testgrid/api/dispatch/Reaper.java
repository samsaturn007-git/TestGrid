package dev.testgrid.api.dispatch;

import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.internal.AttemptRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-04 FR-6 and ARCHITECTURE §4 "Worker crash". One pass, one transaction, two jobs:
 *
 * <ol>
 *   <li><b>Expired leases.</b> An active attempt whose lease ran out (database clock) becomes
 *       {@code ABANDONED} ({@code LEASE_EXPIRED}) and is kept. Its target then:
 *       <ul>
 *         <li>is {@code CANCELLED} if the run has a cancel request (contracts §2.2);
 *         <li>goes back to {@code QUEUED}, undispatched, with {@code not_before = now() + backoff}
 *             while {@code attempt_count < max_attempts} ({@code TARGET_RETRY_SCHEDULED});
 *         <li>otherwise ends {@code INFRA_ERROR}.
 *       </ul>
 *       The dispatcher sends the requeued target with a new generation, so a redelivery of the old
 *       message, or a late call from the old worker, is refused ({@code 409} / {@code 410}).
 *   <li><b>Lost dispatches</b> (CONFLICT_REGISTER N17). A target dispatched longer than {@code
 *       dispatch-timeout-ms} ago and never claimed has lost its message (dead-lettered, or lost on
 *       the way back from the retry queue). It is undispatched so the dispatcher sends a new
 *       generation, or ends {@code INFRA_ERROR} ({@code WORKER_LOST}) once {@code max_attempts}
 *       dispatches in a row went unclaimed. Without this the target would hold a cap slot forever.
 * </ol>
 *
 * Any freed slot nudges the dispatcher after commit.
 */
@Component
public class Reaper {

  private static final Logger log = LoggerFactory.getLogger(Reaper.class);

  private final ReaperRepository reaper;
  private final AttemptRepository events;
  private final ReaperProperties properties;
  private final DispatchSignal signal;
  private final JsonMapper mapper;
  private final DoubleSupplier random;

  @Autowired
  public Reaper(
      ReaperRepository reaper,
      AttemptRepository events,
      ReaperProperties properties,
      DispatchSignal signal,
      JsonMapper mapper) {
    this(
        reaper, events, properties, signal, mapper, () -> ThreadLocalRandom.current().nextDouble());
  }

  Reaper(
      ReaperRepository reaper,
      AttemptRepository events,
      ReaperProperties properties,
      DispatchSignal signal,
      JsonMapper mapper,
      DoubleSupplier random) {
    this.reaper = reaper;
    this.events = events;
    this.properties = properties;
    this.signal = signal;
    this.mapper = mapper;
    this.random = random;
  }

  /** What one pass did; all zero on a quiet system. */
  public record Pass(
      int abandoned, int retried, int failed, int cancelled, int redispatched, int lost) {

    boolean idle() {
      return abandoned + redispatched + lost == 0;
    }
  }

  @Transactional
  public Pass reapOnce() {
    int abandoned = 0;
    int retried = 0;
    int failed = 0;
    int cancelled = 0;
    for (ReaperRepository.ExpiredLease lease : reaper.lockExpiredLeases(properties.batchSize())) {
      abandoned++;
      switch (abandon(lease)) {
        case QUEUED -> retried++;
        case CANCELLED -> cancelled++;
        default -> failed++;
      }
    }
    int redispatched = 0;
    int lost = 0;
    for (ReaperRepository.StuckDispatch stuck :
        reaper.lockStuckDispatches(properties.dispatchTimeoutMs(), properties.batchSize())) {
      switch (recover(stuck)) {
        case QUEUED -> redispatched++;
        case CANCELLED -> cancelled++;
        default -> lost++;
      }
    }
    Pass pass = new Pass(abandoned, retried, failed, cancelled, redispatched, lost);
    if (!pass.idle()) {
      signal.dispatchAfterCommit();
      log.info(
          "reaper pass abandoned={} retried={} failed={} cancelled={} redispatched={} lost={}",
          abandoned,
          retried,
          failed,
          cancelled,
          redispatched,
          lost);
    }
    return pass;
  }

  private TargetStatus abandon(ReaperRepository.ExpiredLease lease) {
    try (var ctx =
        LogContext.open()
            .put("runId", lease.runId())
            .put("targetId", lease.targetId())
            .put("attemptId", lease.attemptId())
            .put("workerId", lease.workerId())) {
      // Run lock before any event insert (AttemptRepository.lockRun): target, then run.
      events.lockRun(lease.runId());
      String detail = "no heartbeat before the lease ran out at " + lease.leasedUntil();
      if (reaper.abandonAttempt(lease.attemptId(), detail) != 1) {
        throw new IllegalStateException("locked active attempt " + lease.attemptId() + " moved");
      }
      Map<String, Object> abandoned = new LinkedHashMap<>();
      abandoned.put("attemptNo", lease.attemptNo());
      abandoned.put("workerId", lease.workerId());
      abandoned.put("errorClass", "LEASE_EXPIRED");
      abandoned.put("leasedUntil", lease.leasedUntil().toString());
      event(
          lease.runId(),
          lease.targetId(),
          lease.attemptId(),
          EventType.ATTEMPT_ABANDONED,
          abandoned);

      if (lease.cancelRequested()) {
        finishActive(lease, TargetStatus.CANCELLED, "CANCELLED");
        log.warn("attempt abandoned (lease expired); run has a cancel request: target CANCELLED");
        return TargetStatus.CANCELLED;
      }
      if (lease.attemptCount() < lease.maxAttempts()) {
        long backoffMs = properties.retryBackoffMs(lease.attemptNo(), random.getAsDouble());
        Instant notBefore = reaper.requeueAfterAttempt(lease.targetId(), backoffMs);
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("reason", "LEASE_EXPIRED");
        retry.put("attemptNo", lease.attemptNo());
        retry.put("nextAttemptNo", lease.attemptNo() + 1);
        retry.put("maxAttempts", lease.maxAttempts());
        retry.put("backoffMs", backoffMs);
        retry.put("notBefore", notBefore.toString());
        event(lease.runId(), lease.targetId(), null, EventType.TARGET_RETRY_SCHEDULED, retry);
        events.recomputeRun(lease.runId());
        log.warn(
            "attempt abandoned (lease expired); retry {} of {} not before {} (backoff {} ms)",
            lease.attemptNo() + 1,
            lease.maxAttempts(),
            notBefore,
            backoffMs);
        return TargetStatus.QUEUED;
      }
      finishActive(lease, TargetStatus.INFRA_ERROR, "LEASE_EXPIRED");
      log.warn(
          "attempt abandoned (lease expired); {} of {} attempts used: target INFRA_ERROR",
          lease.attemptCount(),
          lease.maxAttempts());
      return TargetStatus.INFRA_ERROR;
    }
  }

  private void finishActive(
      ReaperRepository.ExpiredLease lease, TargetStatus status, String errorClass) {
    if (events.finishTarget(lease.targetId(), status) != 1) {
      throw new IllegalStateException("locked active target " + lease.targetId() + " moved");
    }
    targetFinished(
        lease.runId(), lease.targetId(), lease.attemptId(), status, lease.attemptNo(), errorClass);
  }

  private TargetStatus recover(ReaperRepository.StuckDispatch stuck) {
    try (var ctx =
        LogContext.open().put("runId", stuck.runId()).put("targetId", stuck.targetId())) {
      events.lockRun(stuck.runId());
      if (stuck.cancelRequested()) {
        finishQueued(stuck, TargetStatus.CANCELLED, "CANCELLED");
        log.warn("lost dispatch; run has a cancel request: target CANCELLED");
        return TargetStatus.CANCELLED;
      }
      if (stuck.unclaimedDispatches() >= stuck.maxAttempts()) {
        finishQueued(stuck, TargetStatus.INFRA_ERROR, "WORKER_LOST");
        log.error(
            "{} dispatches in a row were never claimed (last generation {}): target INFRA_ERROR",
            stuck.unclaimedDispatches(),
            stuck.dispatchGeneration());
        return TargetStatus.INFRA_ERROR;
      }
      if (reaper.undispatch(stuck.targetId()) != 1) {
        throw new IllegalStateException("locked dispatched target " + stuck.targetId() + " moved");
      }
      Map<String, Object> retry = new LinkedHashMap<>();
      retry.put("reason", "DISPATCH_TIMEOUT");
      retry.put("dispatchGeneration", stuck.dispatchGeneration());
      retry.put("dispatchedAt", stuck.dispatchedAt().toString());
      retry.put("unclaimedDispatches", stuck.unclaimedDispatches());
      event(stuck.runId(), stuck.targetId(), null, EventType.TARGET_RETRY_SCHEDULED, retry);
      log.warn(
          "dispatch generation {} unclaimed since {}; dispatching again",
          stuck.dispatchGeneration(),
          stuck.dispatchedAt());
      return TargetStatus.QUEUED;
    }
  }

  private void finishQueued(
      ReaperRepository.StuckDispatch stuck, TargetStatus status, String errorClass) {
    if (reaper.finishQueued(stuck.targetId(), status.name()) != 1) {
      throw new IllegalStateException("locked QUEUED target " + stuck.targetId() + " moved");
    }
    targetFinished(stuck.runId(), stuck.targetId(), null, status, null, errorClass);
  }

  private void targetFinished(
      UUID runId,
      UUID targetId,
      UUID attemptId,
      TargetStatus status,
      Integer attemptNo,
      String errorClass) {
    Map<String, Object> finished = new LinkedHashMap<>();
    finished.put("status", status.name());
    finished.put("attemptNo", attemptNo);
    finished.put("errorClass", errorClass);
    event(runId, targetId, attemptId, EventType.TARGET_FINISHED, finished);
    var run = events.recomputeRun(runId);
    if (run.finishedNow()) {
      event(runId, null, null, EventType.RUN_FINISHED, Map.of("status", run.status().name()));
    }
  }

  private void event(
      UUID runId, UUID targetId, UUID attemptId, EventType type, Map<String, ?> payload) {
    events.insertEvent(runId, targetId, attemptId, type, mapper.writeValueAsString(payload));
  }
}
