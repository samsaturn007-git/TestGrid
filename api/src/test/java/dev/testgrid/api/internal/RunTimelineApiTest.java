package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.dispatch.Reaper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * PRD-06 T5: heartbeat history, case start offsets, artifacts in results, and {@code GET
 * /runs/{runId}/timeline} over real HTTP against Testcontainers PostgreSQL.
 */
class RunTimelineApiTest extends WorkerApiTestBase {

  @Autowired Reaper reaper;

  static String passed(String key, int offset) {
    return "{\"caseKey\":\""
        + key
        + "\",\"status\":\"PASSED\",\"durationMs\":1200,\"retryCount\":0,"
        + "\"errorClass\":null,\"errorMessage\":null,\"startOffsetMs\":"
        + offset
        + "}";
  }

  String timeline(String runId) {
    var response = get(publicPort, "/api/v1/runs/" + runId + "/timeline");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  @Test
  void everyHeartbeatIsKeptAndTheTimelineGroupsEventsByAttempt() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    lease(claim, "heartbeat");
    lease(claim, "heartbeat");
    String cases =
        checkoutResults(
            Map.of(
                "cart-total", passed("cart-total", 1500), "add-to-cart", passed("add-to-cart", 0)));
    assertThat(
            complete(
                    claim,
                    "attempt-" + claim.attemptId() + "-final",
                    "COMPLETED_WITH_RESULTS",
                    cases)
                .statusCode())
        .isEqualTo(200);

    assertThat(
            count(
                "SELECT count(*) FROM attempt_heartbeats WHERE attempt_id = '"
                    + claim.attemptId()
                    + "'"))
        .isEqualTo(2);
    String body = timeline(runId);
    assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("PASSED");
    assertThat(JsonPath.<Integer>read(body, "$.heartbeatIntervalSeconds")).isEqualTo(10);
    assertThat(JsonPath.<Integer>read(body, "$.gapThresholdMs")).isEqualTo(20_000);
    assertThat(JsonPath.<List<String>>read(body, "$.runEvents[*].type"))
        .containsExactly("RUN_CREATED", "RUN_FINISHED");
    assertThat(JsonPath.<List<String>>read(body, "$.targets[0].attempts[0].heartbeats")).hasSize(2);
    assertThat(JsonPath.<List<Object>>read(body, "$.targets[0].attempts[0].gaps")).isEmpty();
    assertThat(JsonPath.<List<String>>read(body, "$.targets[0].attempts[0].events[*].type"))
        .startsWith("TARGET_CLAIMED", "TARGET_STARTED")
        .contains("CASE_FINISHED")
        .endsWith("TARGET_FINISHED");
    assertThat(
            JsonPath.<List<Integer>>read(
                body,
                "$.targets[0].attempts[0].caseResults[?(@.caseKey=='cart-total')].startOffsetMs"))
        .containsExactly(1500);
    assertThat(
            JsonPath.<List<Object>>read(
                body,
                "$.targets[0].attempts[0].caseResults[?(@.caseKey=='apply-coupon')].startOffsetMs"))
        .containsExactly((Object) null);
    // The results endpoint carries the same start offsets and (no) artifacts.
    String results = get(publicPort, "/api/v1/runs/" + runId + "/results").body();
    assertThat(JsonPath.<List<Object>>read(results, "$.targets[0].attempts[0].artifacts"))
        .isEmpty();
    assertThat(
            JsonPath.<List<Integer>>read(
                results,
                "$.targets[0].attempts[0].caseResults[?(@.caseKey=='cart-total')].startOffsetMs"))
        .containsExactly(1500);
  }

  @Test
  void ac5AnAbandonedAttemptShowsItsHeartbeatGapAboveTheRetry() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    UUID attemptId = UUID.fromString(claim.attemptId());
    // The worker was claimed a minute ago, beat once, then went silent (kill -9).
    jdbc.sql(
            "UPDATE attempts SET claimed_at = now() - interval '60 seconds',"
                + " started_at = now() - interval '59 seconds',"
                + " leased_until = now() - interval '1 second' WHERE id = :id")
        .param("id", attemptId)
        .update();
    jdbc.sql(
            "INSERT INTO attempt_heartbeats (attempt_id, beat_at) VALUES (:id, now() - interval '49 seconds')")
        .param("id", attemptId)
        .update();
    reaper.reapOnce();

    String body = timeline(runId);
    assertThat(JsonPath.<String>read(body, "$.targets[0].attempts[0].attempt.status"))
        .isEqualTo("ABANDONED");
    assertThat(JsonPath.<String>read(body, "$.targets[0].attempts[0].attempt.errorClass"))
        .isEqualTo("LEASE_EXPIRED");
    List<Integer> gaps = JsonPath.read(body, "$.targets[0].attempts[0].gaps[*].durationMs");
    assertThat(gaps).hasSize(1);
    assertThat(gaps.getFirst()).isBetween(48_000, 52_000);
    assertThat(JsonPath.<List<String>>read(body, "$.targets[0].attempts[0].events[*].type"))
        .contains("ATTEMPT_ABANDONED");
    assertThat(JsonPath.<List<String>>read(body, "$.targets[0].events[*].type"))
        .contains("TARGET_RETRY_SCHEDULED");

    // The retry is claimed after the backoff; it is listed after the abandoned attempt.
    jdbc.sql("UPDATE run_targets SET not_before = NULL").update();
    Claim second = claim("chromium");
    String again = timeline(runId);
    assertThat(JsonPath.<List<Integer>>read(again, "$.targets[0].attempts[*].attempt.attemptNo"))
        .containsExactly(1, 2);
    assertThat(JsonPath.<String>read(again, "$.targets[0].attempts[1].attempt.id"))
        .isEqualTo(second.attemptId());
  }

  @Test
  void anActiveAttemptWhoseWorkerStoppedShowsAGrowingGap() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    jdbc.sql(
            "UPDATE attempts SET claimed_at = now() - interval '40 seconds',"
                + " started_at = now() - interval '40 seconds' WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();

    String body = timeline(runId);
    assertThat(JsonPath.<String>read(body, "$.targets[0].attempts[0].attempt.status"))
        .isEqualTo("RUNNING");
    assertThat(JsonPath.<List<Integer>>read(body, "$.targets[0].attempts[0].gaps[*].durationMs"))
        .singleElement()
        .satisfies(ms -> assertThat(ms).isGreaterThan(39_000));
  }

  @Test
  void startOffsetsAreValidatedAndHeartbeatsAreAppendOnly() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    lease(claim, "heartbeat");
    String tooLate = checkoutResults(Map.of("cart-total", passed("cart-total", 999_999_999)));
    String negative = checkoutResults(Map.of("cart-total", passed("cart-total", -1)));

    assertThat(
            complete(
                    claim,
                    "attempt-" + claim.attemptId() + "-final",
                    "COMPLETED_WITH_RESULTS",
                    tooLate)
                .statusCode())
        .isEqualTo(422);
    assertThat(
            complete(
                    claim,
                    "attempt-" + claim.attemptId() + "-final",
                    "COMPLETED_WITH_RESULTS",
                    negative)
                .statusCode())
        .isEqualTo(400);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM attempt_heartbeats").update())
        .isInstanceOf(DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.sql("UPDATE attempt_heartbeats SET beat_at = now()").update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anUnknownRunHasNoTimeline() {
    assertThat(get(publicPort, "/api/v1/runs/" + UUID.randomUUID() + "/timeline").statusCode())
        .isEqualTo(404);
  }
}
