package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-9 results endpoint and the run detail's latest attempt (AC-4 visibility). */
class RunResultsApiTest extends WorkerApiTestBase {

  @Test
  void resultsListEveryAttemptNewestFirstWithItsCases() {
    String runId = createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim first = claim("chromium");
    start(first);
    // What the PRD-04 reaper will do: abandon attempt 1 and requeue the target.
    jdbc.sql(
            "UPDATE attempts SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED',"
                + " finished_at = now() WHERE id = :id")
        .param("id", UUID.fromString(first.attemptId()))
        .update();
    jdbc.sql("UPDATE run_targets SET status = 'QUEUED' WHERE id = :id")
        .param("id", UUID.fromString(first.targetId()))
        .update();
    Claim second = claim("chromium");
    start(second);
    String failed =
        "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":2912,\"retryCount\":1,"
            + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Expected $80.99, received $82.99\"}";
    complete(second, "k", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of("cart-total", failed)));

    var response = get(publicPort, "/api/v1/runs/" + runId + "/results");
    assertThat(response.statusCode()).isEqualTo(200);
    String body = response.body();
    assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("FAILED");
    assertThat(JsonPath.<List<Integer>>read(body, "$.targets[0].attempts[*].attempt.attemptNo"))
        .containsExactly(2, 1);
    assertThat(JsonPath.<List<String>>read(body, "$.targets[0].attempts[*].attempt.status"))
        .containsExactly("FAILED", "ABANDONED");
    assertThat(JsonPath.<List<Object>>read(body, "$.targets[0].attempts[0].caseResults"))
        .hasSize(6);
    assertThat(JsonPath.<List<Object>>read(body, "$.targets[0].attempts[1].caseResults")).isEmpty();
    assertThat(
            JsonPath.<List<String>>read(
                body,
                "$.targets[0].attempts[0].caseResults[?(@.caseKey=='cart-total')].errorClass"))
        .containsExactly("ASSERTION");
    assertThat(body).doesNotContain(first.token()).doesNotContain(second.token());
  }

  @Test
  void ac4AnExpiredLeaseIsVisibleAndTheTargetIsNotTerminal() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    jdbc.sql("UPDATE attempts SET leased_until = now() - interval '5 seconds' WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();

    String run = get(publicPort, "/api/v1/runs/" + runId).body();
    assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("RUNNING");
    assertThat(JsonPath.<String>read(run, "$.targets[0].status")).isEqualTo("RUNNING");
    assertThat(JsonPath.<String>read(run, "$.targets[0].latestAttempt.status"))
        .isEqualTo("RUNNING");
    assertThat(JsonPath.<Boolean>read(run, "$.targets[0].latestAttempt.leaseExpired")).isTrue();
    assertThat(JsonPath.<Object>read(run, "$.targets[0].finishedAt")).isNull();
  }

  @Test
  void aTargetWithoutAttemptsHasANullLatestAttempt() {
    String runId = createRun("checkout-smoke", null, "firefox");
    String run = get(publicPort, "/api/v1/runs/" + runId).body();
    assertThat(JsonPath.<Object>read(run, "$.targets[0].latestAttempt")).isNull();
    assertThat(
            JsonPath.<List<Object>>read(
                get(publicPort, "/api/v1/runs/" + runId + "/results").body(),
                "$.targets[0].attempts"))
        .isEmpty();
  }

  @Test
  void unknownRunIs404() {
    assertThat(get(publicPort, "/api/v1/runs/" + UUID.randomUUID() + "/results").statusCode())
        .isEqualTo(404);
  }
}
