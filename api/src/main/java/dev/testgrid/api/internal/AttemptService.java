package dev.testgrid.api.internal;

import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.common.Redactor;
import dev.testgrid.api.dispatch.DispatchSignal;
import dev.testgrid.api.dispatch.SchedulerSettingsRepository;
import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.TargetOutcome;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.internal.AttemptRepository.LockedAttempt;
import dev.testgrid.api.internal.WorkerApi.CaseResult;
import dev.testgrid.api.internal.WorkerApi.CompletionRequest;
import dev.testgrid.api.internal.WorkerApi.CompletionStatus;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lease protocol (PRD-03 FR-3/FR-4, ARCHITECTURE §4). Each method is one transaction that locks
 * the rows it changes, checks the lease against the database clock and only then writes. A late,
 * duplicate or forged call is answered with a Problem and logged; it never changes a terminal row.
 */
@Service
public class AttemptService {

  private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private final AttemptRepository attempts;
  private final InternalApiProperties properties;
  private final SchedulerSettingsRepository settings;
  private final JsonMapper mapper;
  private final DispatchSignal signal;

  public AttemptService(
      AttemptRepository attempts,
      InternalApiProperties properties,
      SchedulerSettingsRepository settings,
      JsonMapper mapper,
      DispatchSignal signal) {
    this.attempts = attempts;
    this.properties = properties;
    this.settings = settings;
    this.mapper = mapper;
    this.signal = signal;
  }

  /** FR-3: claim the oldest eligible target, or empty when there is nothing to run. */
  @Transactional
  public Optional<WorkerApi.ClaimResponse> claimNext(WorkerApi.ClaimRequest request) {
    var claimable = attempts.lockNextClaimable(request.executor(), request.engines());
    if (claimable.isEmpty()) {
      return Optional.empty();
    }
    var target = claimable.get();
    int leaseTtlSeconds = settings.current().leaseTtlSeconds();
    String token = newLeaseToken();
    int attemptNo = target.attemptCount() + 1;
    var attempt =
        attempts.insertAttempt(
            target.targetId(),
            attemptNo,
            request.workerId(),
            Hashing.sha256(token),
            leaseTtlSeconds);
    if (attempts.markTargetClaimed(target.targetId()) != 1) {
      throw new IllegalStateException("locked QUEUED target " + target.targetId() + " moved");
    }
    attempts.recomputeRun(target.runId());
    attempts.insertEvent(
        target.runId(),
        target.targetId(),
        attempt.id(),
        EventType.TARGET_CLAIMED,
        json(Map.of("workerId", request.workerId(), "attemptNo", attemptNo)));

    try (var ctx = context(target.runId(), target.targetId(), attempt.id(), request.workerId())) {
      log.info(
          "claimed target engine={} attemptNo={} leasedUntil={}",
          target.engine().wireName(),
          attemptNo,
          attempt.leasedUntil());
    }
    var fixture =
        target.fixtureMode() == null
            ? null
            : new WorkerApi.Fixture(target.fixtureMode(), target.fixtureSeed());
    return Optional.of(
        new WorkerApi.ClaimResponse(
            attempt.id(),
            attemptNo,
            target.runId(),
            target.targetId(),
            token,
            leaseTtlSeconds,
            properties.heartbeatIntervalSeconds(),
            new WorkerApi.Suite(target.suiteKey(), target.revision()),
            target.engine(),
            attempts.caseKeys(target.suiteId()),
            fixture,
            target.timeoutMs()));
  }

  /** CLAIMED → RUNNING. A repeated start by the lease holder is answered, not applied twice. */
  @Transactional
  public WorkerApi.StartResponse start(UUID attemptId, String leaseToken) {
    LockedAttempt attempt = lockForLeaseHolder(attemptId, leaseToken, "start");
    try (var ctx = context(attempt)) {
      if (attempt.status() == AttemptStatus.RUNNING) {
        Instant leasedUntil = attempts.currentLease(attemptId).orElseThrow();
        return new WorkerApi.StartResponse(attemptId, AttemptStatus.RUNNING, leasedUntil);
      }
      Instant leasedUntil =
          attempts.markStarted(attemptId, settings.current().leaseTtlSeconds()).orElseThrow();
      if (attempts.markTargetRunning(attempt.targetId()) != 1) {
        throw new IllegalStateException("target of a CLAIMED attempt was not CLAIMED");
      }
      attempts.insertEvent(
          attempt.runId(),
          attempt.targetId(),
          attemptId,
          EventType.TARGET_STARTED,
          json(Map.of("workerId", attempt.workerId(), "attemptNo", attempt.attemptNo())));
      log.info("attempt started leasedUntil={}", leasedUntil);
      return new WorkerApi.StartResponse(attemptId, AttemptStatus.RUNNING, leasedUntil);
    }
  }

  @Transactional
  public WorkerApi.HeartbeatResponse heartbeat(UUID attemptId, String leaseToken) {
    LockedAttempt attempt = lockForLeaseHolder(attemptId, leaseToken, "heartbeat");
    Instant leasedUntil =
        attempts.extendLease(attemptId, settings.current().leaseTtlSeconds()).orElseThrow();
    return new WorkerApi.HeartbeatResponse(leasedUntil, attempt.cancelRequested());
  }

  public record Completion(WorkerApi.CompletionResponse response, boolean replay) {}

  /**
   * FR-4: validates the results against the claim, then writes case results, the terminal attempt
   * and target, the recomputed run and the events in this one transaction. The same completion key
   * again replays the stored outcome; a different key after the attempt finished is a conflict.
   */
  @Transactional
  public Completion complete(UUID attemptId, CompletionRequest request) {
    LockedAttempt attempt = lockAndCheckToken(attemptId, request.leaseToken(), "complete");
    try (var ctx = context(attempt)) {
      if (!attempt.status().isActive()) {
        if (request.completionKey().equals(attempt.completionKey())) {
          log.info("duplicate completion replayed key={}", request.completionKey());
          return new Completion(response(attempt), true);
        }
        if (attempt.completionKey() == null) {
          throw rejected(attempt, "complete", ProblemType.LEASE_EXPIRED, "the lease was lost");
        }
        throw rejected(
            attempt,
            "complete",
            ProblemType.IDEMPOTENCY_CONFLICT,
            "attempt "
                + attemptId
                + " already completed with a different completion key ("
                + attempt.status()
                + ")");
      }
      if (!attempt.leaseValid()) {
        throw rejected(attempt, "complete", ProblemType.LEASE_EXPIRED, "the lease has expired");
      }

      List<String> claimed = attempts.caseKeys(attempt.suiteId());
      validate(attempt, claimed, request);
      Result result = resultOf(attempt, request);

      for (CaseResult c : request.caseResults()) {
        String message = Redactor.redact(c.errorMessage());
        attempts.insertCaseResult(attemptId, c, c.errorClass(), message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseKey", c.caseKey());
        payload.put("status", c.status().name());
        payload.put("durationMs", c.durationMs());
        payload.put("retryCount", c.retryCount());
        payload.put("errorClass", c.errorClass() == null ? null : c.errorClass().name());
        attempts.insertEvent(
            attempt.runId(), attempt.targetId(), attemptId, EventType.CASE_FINISHED, json(payload));
      }
      if (attempts.finishAttempt(
              attemptId,
              AttemptStatus.valueOf(result.status().name()),
              result.errorClass(),
              result.detail(),
              request.completionKey())
          != 1) {
        throw new IllegalStateException("locked active attempt " + attemptId + " moved");
      }
      if (attempts.finishTarget(attempt.targetId(), result.status()) != 1) {
        throw new IllegalStateException("target of an active attempt was already terminal");
      }
      Map<String, Object> finished = new LinkedHashMap<>();
      finished.put("status", result.status().name());
      finished.put("attemptNo", attempt.attemptNo());
      finished.put("errorClass", result.errorClass() == null ? null : result.errorClass().name());
      attempts.insertEvent(
          attempt.runId(),
          attempt.targetId(),
          attemptId,
          EventType.TARGET_FINISHED,
          json(finished));
      var run = attempts.recomputeRun(attempt.runId());
      if (run.finishedNow()) {
        attempts.insertEvent(
            attempt.runId(),
            null,
            null,
            EventType.RUN_FINISHED,
            json(Map.of("status", run.status().name())));
      }
      // A session just freed up: let the dispatcher release the next target without waiting a tick.
      signal.dispatchAfterCommit();
      log.info(
          "attempt completed status={} errorClass={} cases={} runStatus={}",
          result.status(),
          result.errorClass(),
          request.caseResults().size(),
          run.status());
      return new Completion(response(attempt), false);
    }
  }

  private record Result(TargetStatus status, ErrorClass errorClass, String detail) {}

  /** Contracts §2.2: results decide; the worker's own status only counts when there are none. */
  private static Result resultOf(LockedAttempt attempt, CompletionRequest request) {
    String detail =
        request.workerError() == null ? null : Redactor.redact(request.workerError().message());
    return switch (request.status()) {
      case COMPLETED_WITH_RESULTS -> {
        var outcome =
            TargetOutcome.of(request.caseResults().stream().map(CaseResult::status).toList());
        ErrorClass errorClass = outcome.errorClass();
        if (outcome.status() == TargetStatus.FAILED || outcome.status() == TargetStatus.TIMED_OUT) {
          CaseStatus wanted =
              outcome.status() == TargetStatus.FAILED ? CaseStatus.FAILED : CaseStatus.TIMED_OUT;
          errorClass =
              request.caseResults().stream()
                  .filter(c -> c.status() == wanted)
                  .map(CaseResult::errorClass)
                  .findFirst()
                  .orElse(ErrorClass.UNKNOWN);
        }
        String why = outcome.errorClass() == ErrorClass.CONFIG ? "no case was executed" : null;
        yield new Result(outcome.status(), errorClass, why);
      }
      case INFRA_ERROR ->
          new Result(TargetStatus.INFRA_ERROR, request.workerError().errorClass(), detail);
      case TIMED_OUT ->
          new Result(
              TargetStatus.TIMED_OUT,
              request.workerError() == null
                  ? ErrorClass.TEST_TIMEOUT
                  : request.workerError().errorClass(),
              detail);
      case CANCELLED -> new Result(TargetStatus.CANCELLED, ErrorClass.CANCELLED, detail);
    };
  }

  /** Everything that makes a well-formed completion still not fit its claim: 422, no writes. */
  private void validate(LockedAttempt attempt, List<String> claimed, CompletionRequest request) {
    List<FieldError> errors = new ArrayList<>();
    List<CaseResult> cases = request.caseResults();
    boolean withResults = request.status() == CompletionStatus.COMPLETED_WITH_RESULTS;

    if (withResults) {
      if (request.workerError() != null) {
        errors.add(new FieldError("workerError", "must be null when results are reported"));
      }
      Set<String> expected = Set.copyOf(claimed);
      Set<String> seen = new HashSet<>();
      long maxDuration = (long) attempt.timeoutMs() + properties.completionGraceMs();
      for (int i = 0; i < cases.size(); i++) {
        CaseResult c = cases.get(i);
        String at = "caseResults[" + i + "]";
        if (!expected.contains(c.caseKey())) {
          errors.add(new FieldError(at + ".caseKey", "not a case of the claimed suite revision"));
        } else if (!seen.add(c.caseKey())) {
          errors.add(new FieldError(at + ".caseKey", "duplicate case key"));
        }
        if (c.durationMs() < 0 || c.durationMs() > maxDuration) {
          errors.add(new FieldError(at + ".durationMs", "must be between 0 and " + maxDuration));
        }
        if (c.retryCount() < 0 || c.retryCount() > attempt.playwrightRetries()) {
          errors.add(
              new FieldError(
                  at + ".retryCount", "must be between 0 and " + attempt.playwrightRetries()));
        }
        if (c.status() == CaseStatus.FLAKY && c.retryCount() < 1) {
          errors.add(new FieldError(at + ".retryCount", "FLAKY means passed after a retry"));
        }
        boolean failed = c.status() == CaseStatus.FAILED || c.status() == CaseStatus.TIMED_OUT;
        if (failed && c.errorClass() == null) {
          errors.add(new FieldError(at + ".errorClass", "required for " + c.status()));
        }
        if ((c.status() == CaseStatus.PASSED || c.status() == CaseStatus.SKIPPED)
            && (c.errorClass() != null || c.errorMessage() != null)) {
          errors.add(new FieldError(at, "a " + c.status() + " case has no error"));
        }
      }
      for (String key : claimed) {
        if (!seen.contains(key)) {
          errors.add(new FieldError("caseResults", "missing case " + key));
        }
      }
    } else {
      if (!cases.isEmpty()) {
        errors.add(
            new FieldError("caseResults", "must be empty when status is " + request.status()));
      }
      if (request.status() == CompletionStatus.INFRA_ERROR && request.workerError() == null) {
        errors.add(new FieldError("workerError", "required for INFRA_ERROR"));
      }
      if (request.status() == CompletionStatus.CANCELLED && !attempt.cancelRequested()) {
        errors.add(new FieldError("status", "the run has no cancel request"));
      }
    }
    if (!errors.isEmpty()) {
      log.warn("completion rejected: {} problems, first: {}", errors.size(), errors.getFirst());
      throw new ApiProblemException(
          ProblemType.INVALID_COMPLETION,
          "the completion does not match attempt " + attempt.id() + "'s claim",
          errors);
    }
  }

  /** Start and heartbeat: the token must match and the attempt must hold a live lease. */
  private LockedAttempt lockForLeaseHolder(UUID attemptId, String leaseToken, String operation) {
    LockedAttempt attempt = lockAndCheckToken(attemptId, leaseToken, operation);
    if (attempt.status() == AttemptStatus.ABANDONED) {
      throw rejected(attempt, operation, ProblemType.LEASE_EXPIRED, "the lease was lost");
    }
    if (!attempt.status().isActive()) {
      throw rejected(
          attempt,
          operation,
          ProblemType.ATTEMPT_NOT_ACTIVE,
          "attempt " + attemptId + " is already " + attempt.status());
    }
    if (!attempt.leaseValid()) {
      throw rejected(attempt, operation, ProblemType.LEASE_EXPIRED, "the lease has expired");
    }
    return attempt;
  }

  private LockedAttempt lockAndCheckToken(UUID attemptId, String leaseToken, String operation) {
    LockedAttempt attempt =
        attempts
            .lockAttempt(attemptId)
            .orElseThrow(() ->
                new ApiProblemException(
                    ProblemType.NOT_FOUND, "attempt " + attemptId + " not found"));
    // Constant-time comparison of the hashes (contracts §7); the token itself is never stored.
    if (!MessageDigest.isEqual(Hashing.sha256(leaseToken), attempt.tokenHash())) {
      throw rejected(
          attempt, operation, ProblemType.LEASE_EXPIRED, "the lease token is not current");
    }
    return attempt;
  }

  private ApiProblemException rejected(
      LockedAttempt attempt, String operation, ProblemType type, String why) {
    try (var ctx = context(attempt)) {
      log.warn(
          "rejected late or invalid {}: {} (attempt status {})", operation, why, attempt.status());
    }
    return new ApiProblemException(type, why + " for attempt " + attempt.id());
  }

  private WorkerApi.CompletionResponse response(LockedAttempt attempt) {
    var outcome = attempts.outcome(attempt.id());
    return new WorkerApi.CompletionResponse(
        attempt.id(),
        outcome.attemptStatus(),
        outcome.errorClass(),
        attempt.targetId(),
        outcome.targetStatus(),
        attempt.runId(),
        outcome.runStatus());
  }

  private static LogContext context(LockedAttempt attempt) {
    return context(attempt.runId(), attempt.targetId(), attempt.id(), attempt.workerId());
  }

  private static LogContext context(UUID runId, UUID targetId, UUID attemptId, String workerId) {
    return LogContext.open()
        .put("runId", runId)
        .put("targetId", targetId)
        .put("attemptId", attemptId)
        .put("workerId", workerId);
  }

  static String newLeaseToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return BASE64URL.encodeToString(bytes);
  }

  private String json(Map<String, ?> payload) {
    return mapper.writeValueAsString(payload);
  }
}
