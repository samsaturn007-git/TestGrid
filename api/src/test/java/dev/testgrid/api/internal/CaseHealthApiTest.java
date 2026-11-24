package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PRD-07 FR-4/FR-5 against Testcontainers PostgreSQL: the window is updated by real completions,
 * feeds rule R4, and is served with the case history.
 */
class CaseHealthApiTest extends WorkerApiTestBase {

  private static final String CASE = "checkout-happy-path";

  private static String failed(String caseKey) {
    return "{\"caseKey\":\""
        + caseKey
        + "\",\"status\":\"FAILED\",\"durationMs\":1800,\"retryCount\":1,"
        + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Error: expect(locator).toBeVisible()"
        + " failed\\nLocator: getByTestId('order-confirmation-banner')\","
        + "\"errorFrames\":[\"suites/checkout-smoke.spec.ts:<anonymous>\"]}";
  }

  private static String result(String caseKey, String status, int durationMs) {
    return "{\"caseKey\":\""
        + caseKey
        + "\",\"status\":\""
        + status
        + "\",\"durationMs\":"
        + durationMs
        + ",\"retryCount\":0,\"errorClass\":null,\"errorMessage\":null}";
  }

  /** One chromium run of checkout-smoke where {@code CASE} gets the given result. */
  private Claim run(String caseResult) {
    createRun("checkout-smoke", "AUTOMATION_FLAKE", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var done =
        complete(
            claim,
            "k-" + UUID.randomUUID(),
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of(CASE, caseResult)));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    return claim;
  }

  private String health(String column) {
    return jdbc.sql(
            "SELECT "
                + column
                + "::text FROM case_health WHERE case_key = :c AND engine = 'chromium'")
        .param("c", CASE)
        .query(String.class)
        .single();
  }

  private String groupCategory(Claim claim) {
    return jdbc.sql(
            "SELECT f.category FROM case_results cr JOIN failure_fingerprints f"
                + " ON f.id = cr.fingerprint_id WHERE cr.attempt_id = :a AND cr.case_key = :c")
        .param("a", UUID.fromString(claim.attemptId()))
        .param("c", CASE)
        .query(String.class)
        .single();
  }

  private String history(String query) {
    var response = get(publicPort, "/api/v1/cases/" + CASE + "/history" + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  @Test
  void int003AnAlternatingCaseIsLabelledFlakyAndItsGroupBecomesAnAutomationFlake() {
    Claim last = null;
    List<String> categories = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      last = run(i % 2 == 0 ? result(CASE, "PASSED", 1500 + i) : failed(CASE));
      if (i % 2 == 1) {
        categories.add(groupCategory(last));
      }
    }
    assertThat(health("recent_outcomes")).isEqualTo("PFPFPF");
    assertThat(health("label")).isEqualTo("FLAKY");
    assertThat(health("reason")).isEqualTo("5 flips in 5 transitions (1.00 ≥ 0.20)");
    // The honest sequence: one failure is UNKNOWN, two in different attempts look like a
    // reproduction until the history has enough results to say the case flips.
    assertThat(categories).containsExactly("UNKNOWN", "PRODUCT_SUSPECTED", "AUTOMATION_FLAKE");
    UUID group =
        jdbc.sql("SELECT fingerprint_id FROM case_results WHERE attempt_id = :a AND case_key = :c")
            .param("a", UUID.fromString(last.attemptId()))
            .param("c", CASE)
            .query(UUID.class)
            .single();
    String detail = get(publicPort, "/api/v1/failure-groups/" + group).body();
    assertThat((String) JsonPath.read(detail, "$.ruleId")).isEqualTo("R4");
    assertThat((List<String>) JsonPath.read(detail, "$.evidence.facts.casesLabelledFlaky"))
        .containsExactly(CASE + " on chromium");

    // The history links each result to its run and shows the label with its reason.
    String body = history("?suiteKey=checkout-smoke&engine=chromium");
    assertThat((String) JsonPath.read(body, "$.title")).isNotBlank();
    assertThat((List<String>) JsonPath.read(body, "$.engines[*].engine"))
        .containsExactly("chromium");
    assertThat((String) JsonPath.read(body, "$.engines[0].health.label")).isEqualTo("FLAKY");
    assertThat((String) JsonPath.read(body, "$.engines[0].health.recentOutcomes"))
        .isEqualTo("PFPFPF");
    assertThat((List<String>) JsonPath.read(body, "$.engines[0].results[*].status"))
        .containsExactly("FAILED", "PASSED", "FAILED", "PASSED", "FAILED", "PASSED");
    assertThat((String) JsonPath.read(body, "$.engines[0].results[0].runId"))
        .isEqualTo(last.runId());
    assertThat((String) JsonPath.read(body, "$.engines[0].results[0].fingerprintId"))
        .isEqualTo(group.toString());
    assertThat((Object) JsonPath.read(body, "$.engines[0].results[1].fingerprintId")).isNull();
  }

  @Test
  void theIncrementalWindowMatchesARecountOfHistoryAndSkipsSkippedResults() {
    String[] statuses = {"PASSED", "FAILED", "SKIPPED", "PASSED", "PASSED", "FLAKY"};
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < 25; i++) {
      String status = statuses[i % statuses.length];
      String body =
          switch (status) {
            case "FAILED" -> failed(CASE);
            case "FLAKY" ->
                "{\"caseKey\":\""
                    + CASE
                    + "\",\"status\":\"FLAKY\",\"durationMs\":2100,\"retryCount\":1,"
                    + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"x\"}";
            default -> result(CASE, status, 1000);
          };
      run(body);
      if (!status.equals("SKIPPED")) {
        expected.append(
            switch (status) {
              case "PASSED" -> 'P';
              case "FAILED" -> 'F';
              default -> 'K';
            });
      }
    }
    String last20 = expected.substring(expected.length() - 20);
    assertThat(health("recent_outcomes")).isEqualTo(last20);
    assertThat(health("observed")).isEqualTo("20");
    String recount =
        jdbc.sql(
                "SELECT string_agg(CASE cr.status WHEN 'PASSED' THEN 'P' WHEN 'FLAKY' THEN 'K'"
                    + " WHEN 'FAILED' THEN 'F' ELSE 'T' END, '' ORDER BY cr.created_at, cr.id)"
                    + " FROM (SELECT * FROM case_results WHERE case_key = :c AND status <> 'SKIPPED'"
                    + " ORDER BY created_at DESC, id DESC LIMIT 20) cr")
            .param("c", CASE)
            .query(String.class)
            .single();
    assertThat(recount).isEqualTo(last20);
    // The other cases passed every time: STABLE with no flips.
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM case_health WHERE case_key <> :c AND label = 'STABLE'"
                        + " AND flip_count = 0 AND observed = 20")
                .param("c", CASE)
                .query(Long.class)
                .single())
        .isEqualTo(5);
  }

  @Test
  void aCaseThatStartsFailingAfterPassingIsFailing() {
    for (int i = 0; i < 10; i++) {
      run(result(CASE, "PASSED", 1000));
    }
    for (int i = 0; i < 3; i++) {
      run(failed(CASE));
    }
    assertThat(health("label")).isEqualTo("FAILING");
    assertThat(health("reason")).isEqualTo("the last 3 results failed");
  }

  @Test
  void fewResultsAreInsufficientDataAndEnginesWithoutResultsHaveNoHealth() {
    for (int i = 0; i < 3; i++) {
      run(result(CASE, "PASSED", 1000 + 100 * i));
    }
    String body = history("?suiteKey=checkout-smoke&limit=2");
    assertThat((List<String>) JsonPath.read(body, "$.engines[*].engine"))
        .containsExactly("chromium", "firefox", "webkit");
    assertThat((String) JsonPath.read(body, "$.engines[0].health.label"))
        .isEqualTo("INSUFFICIENT_DATA");
    assertThat((List<Integer>) JsonPath.read(body, "$.engines[0].results[*].durationMs"))
        .containsExactly(1200, 1100);
    assertThat((Object) JsonPath.read(body, "$.engines[1].health")).isNull();
    assertThat((List<?>) JsonPath.read(body, "$.engines[1].results")).isEmpty();
  }

  @Test
  void badHistoryQueriesAre400AndUnknownCasesAre404() {
    for (String query :
        List.of(
            "",
            "?suiteKey=Checkout_Smoke",
            "?suiteKey=checkout-smoke&engine=CHROMIUM",
            "?suiteKey=checkout-smoke&engine=edge",
            "?suiteKey=checkout-smoke&limit=0",
            "?suiteKey=checkout-smoke&limit=101",
            "?suiteKey=checkout-smoke&limit=ten",
            "?suiteKey=checkout-smoke&suite=x",
            "?suiteKey=checkout-smoke&suiteKey=checkout-smoke")) {
      var response = get(publicPort, "/api/v1/cases/" + CASE + "/history" + query);
      assertThat(response.statusCode()).as(query + ": " + response.body()).isEqualTo(400);
      assertThat(response.body()).contains("/problems/validation-failed");
    }
    assertThat(
            get(publicPort, "/api/v1/cases/no-such-case/history?suiteKey=checkout-smoke")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            get(publicPort, "/api/v1/cases/" + CASE + "/history?suiteKey=no-such-suite")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            get(publicPort, "/api/v1/cases/Bad_Key/history?suiteKey=checkout-smoke").statusCode())
        .isEqualTo(400);
  }
}
