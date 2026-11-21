package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PRD-04 FR-5, contracts §7: {@code POST /internal/v1/targets/{targetId}/claim}. Every refusal is
 * answered before anything is written, so a rejected message leaves no attempt and no event.
 */
class TargetClaimTest extends WorkerApiTestBase {

  @Test
  void theCurrentMessageClaimsAndTheEventRecordsIt() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    UUID messageId = UUID.randomUUID();

    var response =
        internalPost(
            "/internal/v1/targets/" + message.targetId() + "/claim",
            "{\"workerId\":\"w-7\",\"messageId\":\""
                + messageId
                + "\",\"dispatchGeneration\":"
                + message.generation()
                + "}");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.targetId"))
        .isEqualTo(message.targetId().toString());
    assertThat(JsonPath.<String>read(response.body(), "$.runId")).isEqualTo(runId);
    var event =
        row(
            "SELECT payload->>'workerId' AS worker, payload->>'messageId' AS message,"
                + " (payload->>'dispatchGeneration')::int AS generation,"
                + " (payload->>'attemptNo')::int AS attempt_no"
                + " FROM run_events WHERE type = 'TARGET_CLAIMED' AND run_id = :id",
            runId);
    assertThat(event)
        .containsEntry("worker", "w-7")
        .containsEntry("message", messageId.toString())
        .containsEntry("generation", message.generation())
        .containsEntry("attempt_no", 1);
  }

  @Test
  void anOlderGenerationIsStale() {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched old = dispatchOldest("chromium");
    // The target was requeued and dispatched again: generation + 1 is now the only valid one.
    jdbc.sql("UPDATE run_targets SET dispatch_generation = dispatch_generation + 1 WHERE id = :t")
        .param("t", old.targetId())
        .update();

    assertStale(claimTarget("w-1", old.targetId(), old.generation()), "not the current");
    assertThat(claimTarget("w-1", old.targetId(), old.generation() + 1).statusCode())
        .isEqualTo(200);
  }

  @Test
  void aNewerGenerationThanTheTargetsIsStaleToo() {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    assertStale(
        claimTarget("w-1", message.targetId(), message.generation() + 1), "not the current");
  }

  @Test
  void aRequeuedTargetWaitingForANewDispatchIsStale() {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    jdbc.sql("UPDATE run_targets SET dispatched_at = NULL WHERE id = :t")
        .param("t", message.targetId())
        .update();
    assertStale(claimTarget("w-1", message.targetId(), message.generation()), "requeued");
  }

  @Test
  void aTerminalTargetIsStale() {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    var claimed = claimTarget("w-1", message.targetId(), message.generation());
    Claim claim =
        new Claim(
            JsonPath.read(claimed.body(), "$.attemptId"),
            JsonPath.read(claimed.body(), "$.leaseToken"),
            message.targetId().toString(),
            null,
            claimed.body());
    start(claim);
    assertThat(
            complete(claim, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of())).statusCode())
        .isEqualTo(200);

    // A redelivery after the worker died between complete and ack (AC-3, Q-008).
    assertStale(claimTarget("w-2", message.targetId(), message.generation()), "already PASSED");
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(1);
  }

  @Test
  void aRunWithACancelRequestIsStale() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    jdbc.sql("UPDATE runs SET cancel_requested_at = now() WHERE id = :r")
        .param("r", UUID.fromString(runId))
        .update();
    assertStale(claimTarget("w-1", message.targetId(), message.generation()), "cancel request");
  }

  @Test
  void activeAttemptsAtTheCapAre429UntilOneFinishes() {
    createRun("checkout-smoke", "PASS", "chromium", "firefox");
    Dispatched first = dispatchOldest("chromium", "firefox");
    Dispatched second = dispatchOldest("chromium", "firefox");
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 1").update();

    var claimed = claimTarget("w-1", first.targetId(), first.generation());
    assertThat(claimed.statusCode()).isEqualTo(200);
    var refused = claimTarget("w-2", second.targetId(), second.generation());
    assertThat(refused.statusCode()).isEqualTo(429);
    assertThat(refused.body()).contains("/problems/capacity-exhausted").contains("1 of 1");
    assertThat(one("SELECT status FROM run_targets WHERE id = :id", second.targetId()))
        .as("still dispatched and QUEUED, so the message can come back from the retry queue")
        .isEqualTo("QUEUED");
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(1);

    Claim claim =
        new Claim(
            JsonPath.read(claimed.body(), "$.attemptId"),
            JsonPath.read(claimed.body(), "$.leaseToken"),
            first.targetId().toString(),
            null,
            claimed.body());
    complete(
        claim.attemptId(),
        claim.token(),
        "k",
        "INFRA_ERROR",
        "[]",
        "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"test\"}");
    assertThat(claimTarget("w-2", second.targetId(), second.generation()).statusCode())
        .isEqualTo(200);
  }

  @Test
  void anUnknownTargetIs404() {
    var response = claimTarget("w-1", UUID.randomUUID(), 1);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("/problems/not-found");
  }

  @Test
  void malformedClaimsAre400() {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");
    String path = "/internal/v1/targets/" + message.targetId() + "/claim";
    assertThat(internalPost(path, "{\"workerId\":\"w\",\"dispatchGeneration\":1}").statusCode())
        .as("messageId missing")
        .isEqualTo(400);
    assertThat(
            internalPost(
                    path,
                    "{\"workerId\":\"w\",\"messageId\":\""
                        + UUID.randomUUID()
                        + "\",\"dispatchGeneration\":0}")
                .statusCode())
        .as("generation 0 is never dispatched")
        .isEqualTo(400);
    assertThat(
            internalPost(
                    "/internal/v1/targets/not-a-uuid/claim",
                    "{\"workerId\":\"w\",\"messageId\":\""
                        + UUID.randomUUID()
                        + "\",\"dispatchGeneration\":1}")
                .statusCode())
        .isEqualTo(400);
    assertThat(count("SELECT count(*) FROM attempts")).isZero();
  }

  private void assertStale(HttpResponse<String> response, String why) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
    assertThat(response.body()).contains("/problems/stale-dispatch").contains(why);
  }
}
