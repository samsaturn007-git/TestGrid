package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.internal.WorkerApi;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PRD-04 FR-6, AC-4 (Q-004) and CONFLICT_REGISTER N17 against Testcontainers PostgreSQL. A lease
 * "runs out" by moving {@code leased_until} into the past on the database clock, which is exactly
 * what the reaper compares against; nothing else about the attempt is faked.
 */
class ReaperTest extends DispatchTestBase {

  @Autowired Reaper reaper;
  @Autowired ReaperProperties properties;

  @Test
  void anExpiredLeaseIsAbandonedKeptAndRetriedAfterABackoff() {
    UUID runId = createRun(Engine.CHROMIUM);
    UUID target = targetOf(runId);
    dispatcher.dispatchOnce();
    var claim = claim();
    attempts.start(claim.attemptId(), claim.leaseToken());
    expireLease(claim.attemptId());

    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(1, 1, 0, 0, 0, 0));

    var attempt =
        jdbc.sql(
                "SELECT status, error_class, completion_key, finished_at IS NOT NULL AS finished"
                    + " FROM attempts WHERE id = :a")
            .param("a", claim.attemptId())
            .query()
            .singleRow();
    assertThat(attempt)
        .containsEntry("status", "ABANDONED")
        .containsEntry("error_class", "LEASE_EXPIRED")
        .containsEntry("finished", true);
    assertThat(attempt.get("completion_key")).isNull();

    var row =
        jdbc.sql(
                "SELECT status, dispatched_at IS NULL AS undispatched, attempt_count,"
                    + " extract(epoch FROM not_before - now()) * 1000 AS wait_ms"
                    + " FROM run_targets WHERE id = :t")
            .param("t", target)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("status", "QUEUED")
        .containsEntry("undispatched", true)
        .containsEntry("attempt_count", 1);
    // 2 s ± 20 % after the first attempt, minus the few ms this test has spent since.
    assertThat(((Number) row.get("wait_ms")).doubleValue()).isBetween(1400.0, 2400.0);
    assertThat(eventTypes(runId))
        .containsSubsequence("TARGET_CLAIMED", "ATTEMPT_ABANDONED", "TARGET_RETRY_SCHEDULED");
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'reason' FROM run_events"
                        + " WHERE type = 'TARGET_RETRY_SCHEDULED' AND run_id = :r")
                .param("r", runId)
                .query(String.class)
                .single())
        .isEqualTo("LEASE_EXPIRED");
    assertThat(runStatus(runId)).isEqualTo("RUNNING");
    // The abandoned attempt freed its session.
    assertThat(count("SELECT count(*) FROM attempts WHERE status IN ('CLAIMED', 'RUNNING')"))
        .isZero();
  }

  @Test
  void theRetryWaitsForItsBackoffThenGoesOutWithANewGenerationThatFencesTheOldWorker() {
    UUID target = targetOf(createRun(Engine.CHROMIUM));
    dispatcher.dispatchOnce();
    var first = claim();
    attempts.start(first.attemptId(), first.leaseToken());
    expireLease(first.attemptId());
    reaper.reapOnce();

    assertThat(dispatcher.dispatchOnce()).as("not before the backoff").isEmpty();
    jdbc.sql("UPDATE run_targets SET not_before = now() WHERE id = :t").param("t", target).update();
    var redispatched = dispatcher.dispatchOnce();
    assertThat(redispatched)
        .singleElement()
        .extracting(Dispatcher.Dispatched::dispatchGeneration)
        .isEqualTo(2);

    // The old worker wakes up: its redelivered message and its lease are both refused.
    assertThat(problem(() -> attempts.claim(target, request(1))))
        .isEqualTo(ProblemType.STALE_DISPATCH);
    assertThat(problem(() -> attempts.heartbeat(first.attemptId(), first.leaseToken())))
        .isEqualTo(ProblemType.LEASE_EXPIRED);
    assertThat(
            problem(() ->
                attempts.complete(
                    first.attemptId(),
                    new WorkerApi.CompletionRequest(
                        first.leaseToken(),
                        "attempt-" + first.attemptId() + "-final",
                        WorkerApi.CompletionStatus.INFRA_ERROR,
                        List.of(),
                        new WorkerApi.WorkerError(
                            dev.testgrid.api.domain.ErrorClass.WORKER_LOST, "late")))))
        .isEqualTo(ProblemType.LEASE_EXPIRED);

    var second = attempts.claim(target, request(2));
    assertThat(second.attemptNo()).isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT status FROM attempts WHERE id = :a")
                .param("a", first.attemptId())
                .query(String.class)
                .single())
        .as("the abandoned attempt is kept unchanged")
        .isEqualTo("ABANDONED");
  }

  @Test
  void theLastAllowedAttemptEndsTheTargetInfraErrorWithEveryAttemptKept() {
    jdbc.sql("UPDATE scheduler_settings SET max_attempts = 2").update();
    UUID runId = createRun(Engine.CHROMIUM);
    UUID target = targetOf(runId);
    for (int i = 1; i <= 2; i++) {
      jdbc.sql("UPDATE run_targets SET not_before = NULL WHERE id = :t")
          .param("t", target)
          .update();
      dispatcher.dispatchOnce();
      var claim = claim();
      expireLease(claim.attemptId());
      var pass = reaper.reapOnce();
      assertThat(pass.abandoned()).isEqualTo(1);
      assertThat(pass.failed()).isEqualTo(i == 2 ? 1 : 0);
    }

    assertThat(
            jdbc.sql("SELECT status FROM run_targets WHERE id = :t")
                .param("t", target)
                .query(String.class)
                .single())
        .isEqualTo("INFRA_ERROR");
    assertThat(runStatus(runId)).isEqualTo("INFRA_ERROR");
    assertThat(
            jdbc.sql(
                    "SELECT attempt_no || ':' || status FROM attempts WHERE target_id = :t"
                        + " ORDER BY attempt_no")
                .param("t", target)
                .query(String.class)
                .list())
        .containsExactly("1:ABANDONED", "2:ABANDONED");
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'errorClass' FROM run_events"
                        + " WHERE type = 'TARGET_FINISHED' AND run_id = :r")
                .param("r", runId)
                .query(String.class)
                .single())
        .isEqualTo("LEASE_EXPIRED");
    assertThat(eventTypes(runId)).endsWith("TARGET_FINISHED", "RUN_FINISHED");
    // Nothing comes back: the dispatcher has nothing to send, the reaper nothing to reap.
    assertThat(dispatcher.dispatchOnce()).isEmpty();
    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(0, 0, 0, 0, 0, 0));
  }

  @Test
  void anExpiredLeaseInARunWithACancelRequestEndsCancelled() {
    UUID runId = createRun(Engine.CHROMIUM);
    dispatcher.dispatchOnce();
    var claim = claim();
    jdbc.sql("UPDATE runs SET cancel_requested_at = now() WHERE id = :r")
        .param("r", runId)
        .update();
    expireLease(claim.attemptId());

    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(1, 0, 0, 1, 0, 0));
    assertThat(runStatus(runId)).isEqualTo("CANCELLED");
  }

  @Test
  void liveLeasesAreLeftAlone() {
    // A finished attempt can't be reaped either: the attempts_terminal_is_final trigger (V3)
    // refuses any change to it, even backdating its lease for a test.
    createRun(Engine.CHROMIUM, Engine.FIREFOX);
    dispatcher.dispatchOnce();
    var live = claim();
    completeAsInfraError(claim());

    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(0, 0, 0, 0, 0, 0));
    assertThat(
            jdbc.sql("SELECT status FROM attempts WHERE id = :a")
                .param("a", live.attemptId())
                .query(String.class)
                .single())
        .isEqualTo("CLAIMED");
  }

  @Test
  void aDispatchNeverClaimedIsSentAgainWithANewGeneration() {
    UUID runId = createRun(Engine.CHROMIUM);
    UUID target = targetOf(runId);
    dispatcher.dispatchOnce();
    // The message was dead-lettered (or lost on its way back from the retry queue).
    loseDispatch(target);

    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(0, 0, 0, 0, 1, 0));
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'reason' FROM run_events"
                        + " WHERE type = 'TARGET_RETRY_SCHEDULED' AND run_id = :r")
                .param("r", runId)
                .query(String.class)
                .single())
        .isEqualTo("DISPATCH_TIMEOUT");
    assertThat(dispatcher.dispatchOnce())
        .singleElement()
        .extracting(Dispatcher.Dispatched::dispatchGeneration)
        .isEqualTo(2);
    assertThat(problem(() -> attempts.claim(target, request(1))))
        .isEqualTo(ProblemType.STALE_DISPATCH);
    assertThat(attempts.claim(target, request(2)).attemptNo()).isEqualTo(1);
  }

  @Test
  void maxAttemptsLostDispatchesInARowEndTheTargetInfraErrorWorkerLost() {
    UUID runId = createRun(Engine.CHROMIUM);
    UUID target = targetOf(runId);
    for (int i = 1; i <= 3; i++) {
      assertThat(dispatcher.dispatchOnce()).hasSize(1);
      loseDispatch(target);
      var pass = reaper.reapOnce();
      assertThat(pass.lost()).as("pass " + i).isEqualTo(i == 3 ? 1 : 0);
    }
    assertThat(
            jdbc.sql("SELECT status FROM run_targets WHERE id = :t")
                .param("t", target)
                .query(String.class)
                .single())
        .isEqualTo("INFRA_ERROR");
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'errorClass' FROM run_events"
                        + " WHERE type = 'TARGET_FINISHED' AND run_id = :r")
                .param("r", runId)
                .query(String.class)
                .single())
        .isEqualTo("WORKER_LOST");
    assertThat(runStatus(runId)).isEqualTo("INFRA_ERROR");
    assertThat(count("SELECT count(*) FROM attempts")).isZero();
  }

  @Test
  void aDispatchYoungerThanTheTimeoutIsLeftAlone() {
    createRun(Engine.CHROMIUM);
    dispatcher.dispatchOnce();
    assertThat(reaper.reapOnce()).isEqualTo(new Reaper.Pass(0, 0, 0, 0, 0, 0));
    assertThat(properties.dispatchTimeoutMs()).isEqualTo(300_000);
  }

  private void expireLease(UUID attemptId) {
    jdbc.sql("UPDATE attempts SET leased_until = now() - interval '1 second' WHERE id = :a")
        .param("a", attemptId)
        .update();
  }

  private void loseDispatch(UUID target) {
    jdbc.sql(
            "UPDATE run_targets SET dispatched_at = now() - make_interval(secs => :s)"
                + " WHERE id = :t")
        .param("s", properties.dispatchTimeoutMs() / 1000 + 1)
        .param("t", target)
        .update();
  }

  private static WorkerApi.ClaimRequest request(int generation) {
    return new WorkerApi.ClaimRequest("w-test", UUID.randomUUID(), generation);
  }

  private static ProblemType problem(Runnable call) {
    try {
      call.run();
    } catch (ApiProblemException e) {
      return e.type();
    }
    throw new AssertionError("expected a Problem response");
  }

  private String runStatus(UUID runId) {
    return jdbc.sql("SELECT status FROM runs WHERE id = :r")
        .param("r", runId)
        .query(String.class)
        .single();
  }

  private List<String> eventTypes(UUID runId) {
    return jdbc.sql("SELECT type FROM run_events WHERE run_id = :r ORDER BY id")
        .param("r", runId)
        .query(String.class)
        .list();
  }
}
