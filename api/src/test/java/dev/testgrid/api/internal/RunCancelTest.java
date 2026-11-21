package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** PRD-04 FR-8 and AC-7 (Q-007): {@code POST /api/v1/runs/{runId}/cancel} through real HTTP. */
class RunCancelTest extends WorkerApiTestBase {

  /** Which side won each race, written to target/evidence/ for docs/evidence/PRD-04.md. */
  private static final Map<String, Integer> OUTCOMES =
      new java.util.concurrent.ConcurrentHashMap<>();

  @org.junit.jupiter.api.AfterAll
  static void writeOutcomes() throws java.io.IOException {
    var file = java.nio.file.Path.of("target/evidence/cancel-races.txt");
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, new java.util.TreeMap<>(OUTCOMES) + "\n");
  }

  @Test
  void queuedTargetsAreCancelledAtOnceAndADispatchedMessageBecomesStale() {
    String runId = createRun("checkout-smoke", "PASS", "chromium", "firefox");
    Dispatched dispatched = dispatchOldest("chromium", "firefox");

    var response = cancel(runId);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<String>read(response.body(), "$.cancelRequestedAt")).isNotNull();
    assertThat(JsonPath.<List<String>>read(response.body(), "$.targets[*].status"))
        .containsOnly("CANCELLED");
    assertThat(events(runId))
        .containsExactly(
            "RUN_CREATED",
            "RUN_CANCEL_REQUESTED",
            "TARGET_FINISHED",
            "TARGET_FINISHED",
            "RUN_FINISHED");
    // The message already in the broker: the worker's claim is refused and it acks.
    var claim = claimTarget("w-1", dispatched.targetId(), dispatched.generation());
    assertThat(claim.statusCode()).isEqualTo(409);
    assertThat(claim.body()).contains("already CANCELLED");
    assertThat(count("SELECT count(*) FROM attempts")).isZero();
  }

  @Test
  void anActiveAttemptLearnsOfTheCancelOnItsHeartbeatAndFinishesCancelled() {
    String runId = createRun("checkout-smoke", "PASS", "chromium", "firefox");
    Claim claim = claim("chromium");
    start(claim);

    var response = cancel(runId);
    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("RUNNING");
    assertThat(
            one(
                "SELECT payload::text FROM run_events"
                    + " WHERE type = 'RUN_CANCEL_REQUESTED' AND run_id = :id",
                runId))
        .contains("\"accepted\": true")
        .contains("\"activeTargets\": 1")
        .contains("\"cancelledTargets\": 1");

    var beat = lease(claim, "heartbeat");
    assertThat(JsonPath.<Boolean>read(beat.body(), "$.cancelRequested")).isTrue();
    var done = complete(claim.attemptId(), claim.token(), "k", "CANCELLED", "[]", "null");
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(done.body(), "$.targetStatus")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<String>read(done.body(), "$.runStatus")).isEqualTo("CANCELLED");
  }

  @Test
  void aTerminalRunIs200UnchangedAndTheRequestIsStillRecorded() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    complete(claim, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));

    var response = cancel(runId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("PASSED");
    assertThat(JsonPath.<Object>read(response.body(), "$.cancelRequestedAt")).isNull();
    assertThat(
            one(
                "SELECT payload::text FROM run_events"
                    + " WHERE type = 'RUN_CANCEL_REQUESTED' AND run_id = :id",
                runId))
        .contains("\"accepted\": false")
        .contains("\"runStatus\": \"PASSED\"");
  }

  @Test
  void aSecondCancelIsAcceptedAgainWithoutChangingAnything() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    assertThat(cancel(runId).statusCode()).isEqualTo(202);
    String first = one("SELECT cancel_requested_at::text FROM runs WHERE id = :id", runId);

    assertThat(cancel(runId).statusCode()).isEqualTo(202);
    assertThat(one("SELECT cancel_requested_at::text FROM runs WHERE id = :id", runId))
        .isEqualTo(first);
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'alreadyRequested' FROM run_events"
                        + " WHERE type = 'RUN_CANCEL_REQUESTED' AND run_id = :r ORDER BY id")
                .param("r", UUID.fromString(runId))
                .query(String.class)
                .list())
        .containsExactly("false", "true");
    assertThat(one("SELECT status FROM attempts WHERE id = :id", claim.attemptId()))
        .isEqualTo("CLAIMED");
  }

  @Test
  void anUnknownRunIs404() {
    var response = cancel(UUID.randomUUID().toString());
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("/problems/not-found");
  }

  /**
   * AC-7: a cancel racing with the completion. Whichever commits first wins: the completion keeps
   * its PASSED result (the cancel then finds a terminal run, 200), or the cancel is recorded while
   * the attempt is still RUNNING and the completion, already carrying results, still decides the
   * target. Either way exactly one terminal target state and both requests in the event log.
   */
  @RepeatedTest(10)
  void aCancelRacingTheCompletionLeavesOneTerminalStateAndBothRequestsAudited(
      org.junit.jupiter.api.RepetitionInfo rep) throws Exception {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);

    var responses =
        together(
            () -> complete(claim, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of())),
            () -> {
              // Stagger the cancel 0–36 ms across repetitions so both orders happen.
              Thread.sleep((rep.getCurrentRepetition() - 1) * 4L);
              return cancel(runId);
            });

    assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(200);
    assertThat(responses.get(1).statusCode()).isIn(200, 202);
    OUTCOMES.merge(
        responses.get(1).statusCode() == 200
            ? "complete-vs-cancel: completion first (cancel 200)"
            : "complete-vs-cancel: cancel first (cancel 202)",
        1,
        Integer::sum);
    assertThat(one("SELECT status FROM run_targets WHERE run_id = :id", runId)).isEqualTo("PASSED");
    assertThat(one("SELECT status FROM runs WHERE id = :id", runId)).isEqualTo("PASSED");
    List<String> types = events(runId);
    assertThat(types).filteredOn("TARGET_FINISHED"::equals).hasSize(1);
    assertThat(types).filteredOn("RUN_CANCEL_REQUESTED"::equals).hasSize(1);
    assertThat(types).filteredOn("RUN_FINISHED"::equals).hasSize(1);
  }

  /**
   * A cancel racing a claim of a dispatched target: either the claim wins (attempt CLAIMED, the
   * cancel reaches it on its heartbeat) or the cancel wins (target CANCELLED, claim 409, no
   * attempt). Never both, never neither.
   */
  @RepeatedTest(10)
  void aCancelRacingAClaimEndsOneWayOnly(org.junit.jupiter.api.RepetitionInfo rep)
      throws Exception {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");

    var responses =
        together(
            () -> claimTarget("w-1", message.targetId(), message.generation()),
            () -> {
              // Stagger the cancel 0–36 ms across repetitions so both orders happen.
              Thread.sleep((rep.getCurrentRepetition() - 1) * 4L);
              return cancel(runId);
            });

    assertThat(responses.get(1).statusCode()).isEqualTo(202);
    String target = one("SELECT status FROM run_targets WHERE run_id = :id", runId);
    OUTCOMES.merge(
        "claim-vs-cancel: "
            + (responses.get(0).statusCode() == 200 ? "claim first" : "cancel first"),
        1,
        Integer::sum);
    if (responses.get(0).statusCode() == 200) {
      assertThat(target).isEqualTo("CLAIMED");
      assertThat(count("SELECT count(*) FROM attempts WHERE status = 'CLAIMED'")).isEqualTo(1);
    } else {
      assertThat(responses.get(0).statusCode()).isEqualTo(409);
      assertThat(target).isEqualTo("CANCELLED");
      assertThat(count("SELECT count(*) FROM attempts")).isZero();
    }
  }

  private HttpResponse<String> cancel(String runId) {
    return post(publicPort, "/api/v1/runs/" + runId + "/cancel", "", null);
  }

  private List<String> events(String runId) {
    return jdbc.sql("SELECT type FROM run_events WHERE run_id = :r ORDER BY id")
        .param("r", UUID.fromString(runId))
        .query(String.class)
        .list();
  }

  interface Call {
    HttpResponse<String> run() throws Exception;
  }

  private List<HttpResponse<String>> together(Call a, Call b) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<HttpResponse<String>> fa =
          pool.submit(() -> {
            go.await();
            return a.run();
          });
      Future<HttpResponse<String>> fb =
          pool.submit(() -> {
            go.await();
            return b.run();
          });
      go.countDown();
      return List.of(fa.get(), fb.get());
    }
  }
}
