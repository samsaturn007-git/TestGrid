package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.common.Hashing;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** FR-3/FR-4 state transitions, AC-3 (Q-005 at single-worker level) and AC-5. */
class LeaseProtocolTest extends WorkerApiTestBase {

  @Test
  void nothingQueuedIs204() {
    assertThat(claimResponse("w-1", "chromium").statusCode()).isEqualTo(204);
  }

  @Test
  void aClaimCreatesTheAttemptWithAHashedTokenAndADatabaseClockLease() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium", "firefox");

    assertThat(claim.runId()).isEqualTo(runId);
    assertThat(claim.token()).matches("^[A-Za-z0-9_-]{43}$");
    assertThat(JsonPath.<Integer>read(claim.body(), "$.attemptNo")).isEqualTo(1);
    assertThat(JsonPath.<String>read(claim.body(), "$.engine")).isEqualTo("chromium");
    assertThat(JsonPath.<List<String>>read(claim.body(), "$.caseKeys"))
        .containsExactlyElementsOf(CHECKOUT_CASES);
    assertThat(JsonPath.<String>read(claim.body(), "$.suite.suiteKey")).isEqualTo("checkout-smoke");
    assertThat(JsonPath.<String>read(claim.body(), "$.suite.revision")).matches("^[0-9a-f]{12}$");
    assertThat(JsonPath.<String>read(claim.body(), "$.fixture.mode")).isEqualTo("PASS");
    assertThat(JsonPath.<Integer>read(claim.body(), "$.timeoutMs")).isEqualTo(120000);
    assertThat(JsonPath.<Integer>read(claim.body(), "$.leaseTtlSeconds")).isEqualTo(30);

    Map<String, Object> attempt =
        row(
            "SELECT status, worker_id, lease_token_hash,"
                + " extract(epoch FROM leased_until - claimed_at) AS ttl FROM attempts WHERE id = :id",
            claim.attemptId());
    assertThat(attempt).containsEntry("status", "CLAIMED").containsEntry("worker_id", "w-test");
    assertThat((byte[]) attempt.get("lease_token_hash")).isEqualTo(Hashing.sha256(claim.token()));
    assertThat(((Number) attempt.get("ttl")).doubleValue()).isEqualTo(30.0);
    // Only the hash is stored: the token's bytes appear in no column of the attempts table.
    assertThat(
            count(
                "SELECT count(*) FROM attempts WHERE row_to_json(attempts)::text LIKE '%"
                    + claim.token()
                    + "%'"))
        .isZero();

    assertThat(one("SELECT status FROM run_targets WHERE id = :id", claim.targetId()))
        .isEqualTo("CLAIMED");
    assertThat(one("SELECT status FROM runs WHERE id = :id", runId)).isEqualTo("RUNNING");
    assertThat(
            one(
                "SELECT string_agg(type, ',' ORDER BY id) FROM run_events WHERE run_id = :id",
                runId))
        .isEqualTo("RUN_CREATED,TARGET_CLAIMED");
    // The only target is taken now.
    assertThat(claimResponse("w-2", "chromium").statusCode()).isEqualTo(204);
  }

  @Test
  void claimsMatchOnlyTheWorkersEngines() {
    createRun("checkout-smoke", null, "webkit");
    assertThat(claimResponse("w-1", "chromium", "firefox").statusCode()).isEqualTo(204);
    assertThat(claimResponse("w-1", "webkit").statusCode()).isEqualTo(200);
  }

  @Test
  void claimsTakeTheOldestQueuedTargetFirst() {
    String first = createRun("checkout-smoke", null, "chromium");
    String second = createRun("checkout-smoke", null, "chromium");
    assertThat(claim("chromium").runId()).isEqualTo(first);
    assertThat(claim("chromium").runId()).isEqualTo(second);
  }

  @Test
  void startHeartbeatAndAPassingCompletion() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");

    var started = start(claim);
    assertThat(started.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(started.body(), "$.status")).isEqualTo("RUNNING");
    assertThat(start(claim).statusCode()).as("start is idempotent for the holder").isEqualTo(200);
    assertThat(one("SELECT status FROM run_targets WHERE id = :id", claim.targetId()))
        .isEqualTo("RUNNING");

    jdbc.sql("UPDATE attempts SET leased_until = now() + interval '2 seconds' WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();
    var beat = lease(claim, "heartbeat");
    assertThat(beat.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<Boolean>read(beat.body(), "$.cancelRequested")).isFalse();
    assertThat(
            count(
                "SELECT count(*) FROM attempts WHERE leased_until > now() + interval '25 seconds'"))
        .as("heartbeat extended the lease by the TTL from the database clock")
        .isEqualTo(1);

    var done =
        complete(claim, "attempt-final", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(done.body(), "$.targetStatus")).isEqualTo("PASSED");
    assertThat(JsonPath.<String>read(done.body(), "$.runStatus")).isEqualTo("PASSED");

    assertThat(count("SELECT count(*) FROM case_results")).isEqualTo(6);
    assertThat(
            row("SELECT status, finished_at IS NOT NULL AS done FROM runs WHERE id = :id", runId))
        .containsEntry("status", "PASSED")
        .containsEntry("done", true);
    assertThat(
            one(
                "SELECT string_agg(DISTINCT type, ',' ORDER BY type) FROM run_events WHERE run_id = :id",
                runId))
        .isEqualTo(
            "CASE_FINISHED,RUN_CREATED,RUN_FINISHED,TARGET_CLAIMED,TARGET_FINISHED,TARGET_STARTED");
    assertThat(lease(claim, "heartbeat").statusCode())
        .as("no heartbeat after the end")
        .isEqualTo(409);
    assertThat(start(claim).statusCode()).isEqualTo(409);
  }

  @Test
  void ac3DuplicateCompletionWithTheSameKeyReplaysAndChangesNothing() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    String failed =
        "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":2912,\"retryCount\":1,"
            + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Expected $80.99, received $82.99\"}";
    String results = checkoutResults(Map.of("cart-total", failed));
    var first = complete(claim, "k-1", "COMPLETED_WITH_RESULTS", results);
    assertThat(first.statusCode()).isEqualTo(200);
    String eventsBefore =
        one("SELECT count(*)::text FROM run_events WHERE run_id = :id", claim.runId());

    var again = complete(claim, "k-1", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.headers().firstValue("Idempotent-Replay")).hasValue("true");
    assertThat(tree(again.body())).isEqualTo(tree(first.body()));
    assertThat(JsonPath.<String>read(again.body(), "$.targetStatus")).isEqualTo("FAILED");
    assertThat(JsonPath.<String>read(again.body(), "$.errorClass")).isEqualTo("ASSERTION");
    assertThat(count("SELECT count(*) FROM case_results")).isEqualTo(6);
    assertThat(one("SELECT count(*)::text FROM run_events WHERE run_id = :id", claim.runId()))
        .isEqualTo(eventsBefore);

    var other = complete(claim, "k-2", "COMPLETED_WITH_RESULTS", results);
    assertThat(other.statusCode()).isEqualTo(409);
    assertThat(other.body()).contains("/problems/idempotency-conflict");
  }

  @Test
  void ac3StaleLeaseIs410AndNothingChanges() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    expireLease(claim);

    var beat = lease(claim, "heartbeat");
    assertThat(beat.statusCode()).isEqualTo(410);
    assertThat(beat.body()).contains("/problems/lease-expired");
    var done = complete(claim, "late", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(done.statusCode()).isEqualTo(410);

    assertThat(count("SELECT count(*) FROM case_results")).isZero();
    assertThat(one("SELECT status FROM attempts WHERE id = :id", claim.attemptId()))
        .isEqualTo("RUNNING");
    assertThat(one("SELECT status FROM run_targets WHERE id = :id", claim.targetId()))
        .isEqualTo("RUNNING");
  }

  @Test
  void q005AWrongLeaseTokenIs410() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    Claim forged =
        new Claim(claim.attemptId(), AttemptService.newLeaseToken(), null, null, claim.body());
    assertThat(start(forged).statusCode()).isEqualTo(410);
    assertThat(
            complete(forged, "x", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of())).statusCode())
        .isEqualTo(410);
    assertThat(one("SELECT status FROM attempts WHERE id = :id", claim.attemptId()))
        .isEqualTo("CLAIMED");
  }

  @Test
  void anUnknownAttemptIs404() {
    var response =
        lease(
            new Claim(UUID.randomUUID().toString(), AttemptService.newLeaseToken(), null, null, ""),
            "heartbeat");
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void q005ALateCompletionAfterTheLeaseWasLostIs410() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    // What the PRD-04 reaper will do; written directly here.
    jdbc.sql(
            "UPDATE attempts SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED',"
                + " finished_at = now() WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();
    var late = complete(claim, "late", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(late.statusCode()).isEqualTo(410);
    assertThat(count("SELECT count(*) FROM case_results")).isZero();
  }

  @Test
  void ac5MalformedCompletionsAre422AndPersistNothing() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    String pass =
        "\"status\":\"PASSED\",\"durationMs\":10,\"retryCount\":0,\"errorClass\":null,"
            + "\"errorMessage\":null}";

    Map<String, String> invalid =
        Map.of(
            "unknown case key",
            checkoutResults(Map.of("cart-total", "{\"caseKey\":\"not-a-case\"," + pass)),
            "negative duration",
            checkoutResults(
                Map.of(
                    "cart-total",
                    "{\"caseKey\":\"cart-total\",\"status\":\"PASSED\",\"durationMs\":-1,"
                        + "\"retryCount\":0,\"errorClass\":null,\"errorMessage\":null}")),
            "duration beyond the suite timeout",
            checkoutResults(
                Map.of(
                    "cart-total",
                    "{\"caseKey\":\"cart-total\",\"status\":\"PASSED\",\"durationMs\":999999999,"
                        + "\"retryCount\":0,\"errorClass\":null,\"errorMessage\":null}")),
            "duplicate case key",
            checkoutResults(Map.of("cart-total", "{\"caseKey\":\"add-to-cart\"," + pass)),
            "missing case",
            "[{\"caseKey\":\"cart-total\"," + pass + "]",
            "failure without an error class",
            checkoutResults(
                Map.of(
                    "cart-total",
                    "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":10,"
                        + "\"retryCount\":0,\"errorClass\":null,\"errorMessage\":\"x\"}")),
            "retries beyond the manifest",
            checkoutResults(
                Map.of(
                    "cart-total",
                    "{\"caseKey\":\"cart-total\",\"status\":\"FLAKY\",\"durationMs\":10,"
                        + "\"retryCount\":2,\"errorClass\":null,\"errorMessage\":null}")));
    invalid.forEach((why, cases) -> {
      var response = complete(claim, "k-" + why.hashCode(), "COMPLETED_WITH_RESULTS", cases);
      assertThat(response.statusCode()).as(why + ": " + response.body()).isEqualTo(422);
      assertThat(response.body()).contains("/problems/invalid-completion");
    });

    var infraWithoutError = complete(claim, "k-infra", "INFRA_ERROR", "[]");
    assertThat(infraWithoutError.statusCode()).isEqualTo(422);
    var cancelWithoutRequest = complete(claim, "k-cancel", "CANCELLED", "[]");
    assertThat(cancelWithoutRequest.statusCode()).isEqualTo(422);

    assertThat(count("SELECT count(*) FROM case_results")).isZero();
    assertThat(one("SELECT status FROM attempts WHERE id = :id", claim.attemptId()))
        .isEqualTo("RUNNING");
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_FINISHED'")).isZero();

    // The same attempt still completes normally afterwards.
    var ok = complete(claim, "k-ok", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThat(ok.statusCode()).isEqualTo(200);
  }

  @Test
  void shapeErrorsAre400() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    var badToken =
        internalPost(
            "/internal/v1/attempts/" + claim.attemptId() + "/start", "{\"leaseToken\":\"short\"}");
    assertThat(badToken.statusCode()).isEqualTo(400);
    var unknownField =
        internalPost(
            "/internal/v1/claims/next",
            "{\"workerId\":\"w\",\"executor\":\"LOCAL\",\"engines\":[\"chromium\"],\"x\":1}");
    assertThat(unknownField.statusCode()).isEqualTo(400);
    var shellWorker =
        internalPost(
            "/internal/v1/claims/next",
            "{\"workerId\":\"w;rm -rf\",\"executor\":\"LOCAL\",\"engines\":[\"chromium\"]}");
    assertThat(shellWorker.statusCode()).isEqualTo(400);
  }

  @Test
  void theWorkersOwnVerdictNeverOverridesResults() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    String timedOut =
        "{\"caseKey\":\"checkout-happy-path\",\"status\":\"TIMED_OUT\",\"durationMs\":20000,"
            + "\"retryCount\":1,\"errorClass\":\"TEST_TIMEOUT\",\"errorMessage\":\"Test timeout\"}";
    var done =
        complete(
            claim,
            "k",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of("checkout-happy-path", timedOut)));
    assertThat(JsonPath.<String>read(done.body(), "$.targetStatus")).isEqualTo("TIMED_OUT");
    assertThat(JsonPath.<String>read(done.body(), "$.errorClass")).isEqualTo("TEST_TIMEOUT");
    assertThat(JsonPath.<String>read(done.body(), "$.runStatus")).isEqualTo("TIMED_OUT");
  }

  @Test
  void zeroExecutedCasesIsInfraErrorConfig() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    StringBuilder skipped = new StringBuilder("[");
    for (String key : CHECKOUT_CASES) {
      skipped
          .append(skipped.length() > 1 ? "," : "")
          .append("{\"caseKey\":\"")
          .append(key)
          .append("\",\"status\":\"SKIPPED\",\"durationMs\":0,\"retryCount\":0,")
          .append("\"errorClass\":null,\"errorMessage\":null}");
    }
    var done = complete(claim, "k", "COMPLETED_WITH_RESULTS", skipped.append(']').toString());
    assertThat(JsonPath.<String>read(done.body(), "$.targetStatus")).isEqualTo("INFRA_ERROR");
    assertThat(JsonPath.<String>read(done.body(), "$.errorClass")).isEqualTo("CONFIG");
  }

  @Test
  void anInfraErrorStoresTheRedactedWorkerError() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    String error =
        "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"launch failed, Authorization: Bearer "
            + TOKEN
            + " lease "
            + claim.token()
            + "\"}";
    var done = complete(claim.attemptId(), claim.token(), "k", "INFRA_ERROR", "[]", error);
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(done.body(), "$.targetStatus")).isEqualTo("INFRA_ERROR");
    String stored =
        one("SELECT error_detail_redacted FROM attempts WHERE id = :id", claim.attemptId());
    assertThat(stored)
        .startsWith("launch failed")
        .doesNotContain(TOKEN)
        .doesNotContain(claim.token());
  }

  @Test
  void theDatabaseAllowsOneActiveAttemptPerTargetAndFreezesTerminalAttempts() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    UUID target = UUID.fromString(claim.targetId());
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO attempts (target_id, attempt_no, worker_id, status,"
                        + " lease_token_hash, leased_until) VALUES (:t, 2, 'w', 'CLAIMED', :h, now())")
                .param("t", target)
                .param("h", new byte[32])
                .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("attempts_one_active_per_target");

    complete(claim, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    assertThatThrownBy(() ->
            jdbc.sql("UPDATE attempts SET status = 'RUNNING', finished_at = NULL WHERE id = :id")
                .param("id", UUID.fromString(claim.attemptId()))
                .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("is terminal");
    assertThatThrownBy(() -> jdbc.sql("UPDATE case_results SET status = 'FAILED'").update())
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM case_results").update())
        .hasMessageContaining("immutable");
    assertThat(jdbc.sql("UPDATE case_results SET fingerprint_id = gen_random_uuid()").update())
        .as("PRD-07 may still link a fingerprint")
        .isEqualTo(6);
  }

  private void expireLease(Claim claim) {
    jdbc.sql("UPDATE attempts SET leased_until = now() - interval '1 second' WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();
  }

  private Object tree(String json) {
    return mapper.readTree(json);
  }
}
