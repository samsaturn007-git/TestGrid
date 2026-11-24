package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PRD-08 FR-1 / FR-2 through the real API against Testcontainers PostgreSQL: the versioned policy,
 * the evaluation of finished runs (CI-001, CI-002, CI-003 at the API level) and its idempotency.
 */
class QualityApiTest extends WorkerApiTestBase {

  private static final String POLICY_PATH =
      "/api/v1/projects/" + LocalProject.ID + "/quality-policy";

  private HttpResponse<String> put(String path, String body) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + path))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String policyWith(String field, String value) {
    return DEFAULT_POLICY.replaceFirst("\"" + field + "\":[^,{]+", "\"" + field + "\":" + value);
  }

  private String finishChromium(Map<String, String> overrides) {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var done =
        complete(
            claim, "k-" + UUID.randomUUID(), "COMPLETED_WITH_RESULTS", checkoutResults(overrides));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    return runId;
  }

  private String quality(String runId) {
    var response = get(publicPort, "/api/v1/runs/" + runId + "/quality");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  private static String cartTotalFailed() {
    return "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":1300,\"retryCount\":1,"
        + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Error: expect(received).toBe(expected)\"}";
  }

  // ---- policy ------------------------------------------------------------------------------

  @Test
  void theSeededPolicyIsVersionOneAndActive() {
    var response = get(publicPort, POLICY_PATH);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat((Integer) JsonPath.read(response.body(), "$.version")).isEqualTo(1);
    assertThat((Boolean) JsonPath.read(response.body(), "$.active")).isTrue();
    assertThat((List<String>) JsonPath.read(response.body(), "$.policy.requiredTargets"))
        .containsExactly("local:chromium");
    assertThat((Double) JsonPath.read(response.body(), "$.policy.maxFlakyRate")).isEqualTo(0.2);
    assertThat((String) JsonPath.read(response.body(), "$.policy.quarantine.autoMode"))
        .isEqualTo("SUGGEST");
    assertThat((List<?>) JsonPath.read(response.body(), "$.versions")).hasSize(1);
  }

  @Test
  void putAlwaysCreatesTheNextVersionAndAuditsTheReason() {
    String body =
        "{\"reason\":\"gate firefox too\",\"policy\":"
            + policyWith("requiredTargets", "[\"local:chromium\",\"local:firefox\"]")
            + "}";
    var first = put(POLICY_PATH, body);
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    assertThat((Integer) JsonPath.read(first.body(), "$.version")).isEqualTo(2);
    var second = put(POLICY_PATH, body);
    assertThat((Integer) JsonPath.read(second.body(), "$.version")).isEqualTo(3);
    assertThat((List<Integer>) JsonPath.read(second.body(), "$.versions[*].version"))
        .containsExactly(3, 2, 1);
    assertThat((List<Boolean>) JsonPath.read(second.body(), "$.versions[*].active"))
        .containsExactly(true, false, false);

    var old = get(publicPort, POLICY_PATH + "?version=1");
    assertThat(old.statusCode()).isEqualTo(200);
    assertThat((Boolean) JsonPath.read(old.body(), "$.active")).isFalse();
    assertThat((List<String>) JsonPath.read(old.body(), "$.policy.requiredTargets"))
        .containsExactly("local:chromium");

    assertThat(
            count(
                "SELECT count(*) FROM audit_events WHERE action = 'QUALITY_POLICY_CHANGED'"
                    + " AND object_type = 'quality_policy'"))
        .isEqualTo(2);
    String metadata =
        jdbc.sql(
                "SELECT metadata_redacted::text FROM audit_events WHERE action ="
                    + " 'QUALITY_POLICY_CHANGED' ORDER BY occurred_at DESC, id LIMIT 1")
            .query(String.class)
            .single();
    assertThat(metadata).contains("\"fromVersion\": 2").contains("\"toVersion\": 3");
    assertThat(metadata).contains("gate firefox too");
  }

  @Test
  void aPolicyVersionCannotBeEditedInTheDatabase() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql("UPDATE quality_policies SET policy = '{}'::jsonb").update()))
        .hasMessageContaining("immutable");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql("DELETE FROM quality_policies").update()))
        .hasMessageContaining("never deleted");
  }

  @Test
  void invalidPoliciesAreRejected() {
    for (String bad :
        List.of(
            "{\"reason\":\"x\",\"policy\":" + policyWith("maxFlakyRate", "1.5") + "}",
            "{\"reason\":\"x\",\"policy\":" + policyWith("maxFailedCases", "-1") + "}",
            "{\"reason\":\"x\",\"policy\":" + policyWith("requiredTargets", "[]") + "}",
            "{\"reason\":\"x\",\"policy\":"
                + policyWith("requiredTargets", "[\"browserstack:chromium\"]")
                + "}",
            "{\"reason\":\"x\",\"policy\":"
                + policyWith("requiredTargets", "[\"local:chromium\",\"local:chromium\"]")
                + "}",
            "{\"reason\":\"x\",\"policy\":" + policyWith("failOnInfraError", "\"true\"") + "}",
            "{\"reason\":\"x\",\"policy\":"
                + DEFAULT_POLICY.replace("\"maxFailedCases\":0,", "")
                + "}",
            "{\"reason\":\"x\",\"policy\":"
                + DEFAULT_POLICY.replace(
                    "{\"requiredTargets\"", "{\"version\":4,\"requiredTargets\"")
                + "}",
            "{\"reason\":\"x\",\"policy\":"
                + DEFAULT_POLICY.replace("\"SUGGEST\"", "\"ALWAYS\"")
                + "}",
            "{\"reason\":\"   \",\"policy\":" + DEFAULT_POLICY + "}",
            "{\"policy\":" + DEFAULT_POLICY + "}")) {
      var response = put(POLICY_PATH, bad);
      assertThat(response.statusCode()).as(bad + " -> " + response.body()).isEqualTo(400);
      assertThat(response.body()).contains("validation-failed");
    }
    assertThat(count("SELECT count(*) FROM quality_policies")).isEqualTo(1);
  }

  @Test
  void policyQueriesAreStrict() {
    assertThat(get(publicPort, POLICY_PATH + "?versoin=1").statusCode()).isEqualTo(400);
    assertThat(get(publicPort, POLICY_PATH + "?version=0").statusCode()).isEqualTo(400);
    assertThat(get(publicPort, POLICY_PATH + "?version=9").statusCode()).isEqualTo(404);
    assertThat(
            get(publicPort, "/api/v1/projects/" + UUID.randomUUID() + "/quality-policy")
                .statusCode())
        .isEqualTo(404);
  }

  // ---- evaluation --------------------------------------------------------------------------

  @Test
  void anUnfinishedRunHasNoEvaluationYet() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    String body = quality(runId);
    assertThat((String) JsonPath.read(body, "$.runStatus")).isEqualTo("QUEUED");
    assertThat((Object) JsonPath.read(body, "$.outcome")).isNull();
    assertThat((Object) JsonPath.read(body, "$.policy")).isNull();
    assertThat((List<?>) JsonPath.read(body, "$.rules")).isEmpty();
    assertThat(count("SELECT count(*) FROM quality_evaluations")).isZero();
  }

  @Test
  void ci001AGreenRunPassesOnceWhateverHowOftenItIsRead() {
    String runId = finishChromium(Map.of());
    String body = quality(runId);
    assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("PASS");
    assertThat((Integer) JsonPath.read(body, "$.policy.version")).isEqualTo(1);
    assertThat((List<String>) JsonPath.read(body, "$.rules[*].ruleId"))
        .containsExactly(
            "required-targets",
            "infra-error",
            "critical-cases",
            "max-failed-cases",
            "max-flaky-rate",
            "duration-regression",
            "quarantine-honored");
    String again = quality(runId);
    assertThat((String) JsonPath.read(again, "$.evaluationId"))
        .isEqualTo(JsonPath.read(body, "$.evaluationId"));
    assertThat(count("SELECT count(*) FROM quality_evaluations")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM run_events WHERE type = 'QUALITY_EVALUATED' AND run_id = '"
                    + runId
                    + "'"))
        .isEqualTo(1);
    String payload =
        jdbc.sql("SELECT payload::text FROM run_events WHERE type = 'QUALITY_EVALUATED'")
            .query(String.class)
            .single();
    assertThat(payload).contains("\"outcome\": \"PASS\"").contains("\"policyVersion\": 1");
  }

  @Test
  void ci002ACriticalFailureFailsAndNamesTheCaseAndEngine() {
    String runId = finishChromium(Map.of("cart-total", cartTotalFailed()));
    String body = quality(runId);
    assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("FAIL");
    assertThat(
            (List<String>)
                JsonPath.read(body, "$.rules[?(@.ruleId=='critical-cases')].evidence[*].caseKey"))
        .containsExactly("cart-total");
    assertThat(
            (List<String>)
                JsonPath.read(body, "$.rules[?(@.ruleId=='critical-cases')].evidence[*].engine"))
        .containsExactly("chromium");
    assertThat(
            (List<Boolean>) JsonPath.read(body, "$.rules[?(@.ruleId=='critical-cases')].blocking"))
        .containsExactly(true);
  }

  @Test
  void ci003AnInfraErrorIsIndeterminate() {
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var done =
        complete(
            claim.attemptId(),
            claim.token(),
            "k-infra",
            "INFRA_ERROR",
            "[]",
            "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"browserType.launch failed\"}");
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    String body = quality(runId);
    assertThat((String) JsonPath.read(body, "$.runStatus")).isEqualTo("INFRA_ERROR");
    assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("INDETERMINATE");
    assertThat(
            (List<String>)
                JsonPath.read(body, "$.rules[?(@.ruleId=='infra-error')].evidence[*].detail"))
        .singleElement()
        .asString()
        .contains("BROWSER_LAUNCH");
  }

  @Test
  void aMissingRequiredTargetIsIndeterminate() {
    var changed =
        put(
            POLICY_PATH,
            "{\"reason\":\"needs firefox\",\"policy\":"
                + policyWith("requiredTargets", "[\"local:firefox\"]")
                + "}");
    assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
    String body = quality(finishChromium(Map.of()));
    assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("INDETERMINATE");
    assertThat((Integer) JsonPath.read(body, "$.policy.version")).isEqualTo(2);
  }

  @Test
  void anEvaluatedRunKeepsItsPolicyVersionAfterThePolicyChanges() {
    String failed = finishChromium(Map.of("cart-total", cartTotalFailed()));
    assertThat((String) JsonPath.read(quality(failed), "$.outcome")).isEqualTo("FAIL");
    var loosened =
        put(
            POLICY_PATH,
            "{\"reason\":\"loosen\",\"policy\":"
                + policyWith("failOnCriticalCase", "false")
                    .replace("\"maxFailedCases\":0", "\"maxFailedCases\":1")
                + "}");
    assertThat(loosened.statusCode()).as(loosened.body()).isEqualTo(200);

    String before = quality(failed);
    assertThat((String) JsonPath.read(before, "$.outcome")).isEqualTo("FAIL");
    assertThat((Integer) JsonPath.read(before, "$.policy.version")).isEqualTo(1);

    String after = quality(finishChromium(Map.of("cart-total", cartTotalFailed())));
    assertThat((String) JsonPath.read(after, "$.outcome")).isEqualTo("PASS");
    assertThat((Integer) JsonPath.read(after, "$.policy.version")).isEqualTo(2);
  }

  @Test
  void aDurationRegressionAgainstLastGreenIsReported() {
    finishChromium(Map.of());
    String slow =
        "{\"caseKey\":\"browse-catalog\",\"status\":\"PASSED\",\"durationMs\":4000,"
            + "\"retryCount\":0,\"errorClass\":null,\"errorMessage\":null}";
    String body = quality(finishChromium(Map.of("browse-catalog", slow)));
    assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("PASS");
    String rule = "$.rules[?(@.ruleId=='duration-regression')]";
    assertThat((List<String>) JsonPath.read(body, rule + ".outcome")).containsExactly("FAIL");
    assertThat((List<Integer>) JsonPath.read(body, rule + ".observed")).containsExactly(1);
    assertThat((List<String>) JsonPath.read(body, rule + ".evidence[*].caseKey"))
        .containsExactly("browse-catalog");
  }

  @Test
  void anUnknownRunIsNotFound() {
    assertThat(get(publicPort, "/api/v1/runs/" + UUID.randomUUID() + "/quality").statusCode())
        .isEqualTo(404);
  }
}
