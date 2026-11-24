package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PRD-07 FR-6 through the real API against Testcontainers PostgreSQL (CMP-001, CMP-002). */
class RunCompareApiTest extends WorkerApiTestBase {

  private static final List<String> ENGINES = List.of("chromium", "firefox", "webkit");

  private static String cartTotalFailed() {
    return "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":5000,\"retryCount\":1,"
        + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Error: expect(locator).toHaveText"
        + "(expected) failed\\nReceived: \\\"$82.99\\\"\","
        + "\"errorFrames\":[\"suites/checkout-smoke.spec.ts:<anonymous>\"]}";
  }

  private static String passed(String caseKey, int durationMs) {
    return "{\"caseKey\":\""
        + caseKey
        + "\",\"status\":\"PASSED\",\"durationMs\":"
        + durationMs
        + ",\"retryCount\":0,\"errorClass\":null,\"errorMessage\":null}";
  }

  /** Creates a run on {@code engines} with an optional branch and returns its ID. */
  private String create(String suiteKey, String fixture, String branch, List<String> engines) {
    StringBuilder targets = new StringBuilder("[");
    for (int i = 0; i < engines.size(); i++) {
      targets
          .append(i == 0 ? "" : ",")
          .append("{\"executor\":\"LOCAL\",\"engine\":\"")
          .append(engines.get(i))
          .append("\"}");
    }
    String body =
        "{\"suiteKey\":\""
            + suiteKey
            + "\",\"targets\":"
            + targets
            + "],\"trigger\":\"MANUAL\",\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"fixture\":{\"mode\":\""
            + fixture
            + "\",\"seed\":42}"
            + (branch == null ? "" : ",\"source\":{\"branch\":\"" + branch + "\"}")
            + "}";
    var response = post(publicPort, "/api/v1/projects/" + LocalProject.ID + "/runs", body, null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    return JsonPath.read(response.body(), "$.id");
  }

  /** Claims, starts and completes each engine's target with {@code cases} (checkout-smoke). */
  private void finish(List<String> engines, Map<String, String> cases) {
    for (String engine : engines) {
      Claim claim = claim(engine);
      start(claim);
      var done =
          complete(
              claim, "k-" + UUID.randomUUID(), "COMPLETED_WITH_RESULTS", checkoutResults(cases));
      assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    }
  }

  private String run(String fixture, String branch, Map<String, String> cases) {
    String id = create("checkout-smoke", fixture, branch, ENGINES);
    finish(ENGINES, cases);
    return id;
  }

  private String compare(String query, int status) {
    var response = get(publicPort, "/api/v1/runs/compare" + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return response.body();
  }

  private static int count(String body, String category) {
    List<Integer> n = JsonPath.read(body, "$.counts[?(@.category=='" + category + "')].count");
    return n.getFirst();
  }

  @Test
  void cmp001APassRunAgainstAProductDefectRunHasCartTotalAsTheOnlyNewFailure() {
    String base = run("PASS", "main", Map.of());
    String head = run("PRODUCT_DEFECT", "main", Map.of("cart-total", cartTotalFailed()));
    String body = compare("?base=" + base + "&head=" + head, 200);
    List<String> newFailures = JsonPath.read(body, "$.rows[?(@.category=='NEW_FAILURE')].caseKey");
    List<String> newFailureEngines =
        JsonPath.read(body, "$.rows[?(@.category=='NEW_FAILURE')].engine");
    assertThat(newFailures).containsExactly("cart-total", "cart-total", "cart-total");
    assertThat(newFailureEngines).containsExactly("chromium", "firefox", "webkit");
    assertThat(count(body, "STILL_PASSING")).isEqualTo(15);
    for (String other :
        List.of("FIXED", "STILL_FAILING", "FLAKY_IN_HEAD", "ADDED", "REMOVED", "NOT_COMPARABLE")) {
      assertThat(count(body, other)).as(other).isZero();
    }
    assertThat((List<?>) JsonPath.read(body, "$.rows")).hasSize(18);
    assertThat((String) JsonPath.read(body, "$.base.id")).isEqualTo(base);
    assertThat((String) JsonPath.read(body, "$.head.fixtureMode")).isEqualTo("PRODUCT_DEFECT");
    assertThat((Boolean) JsonPath.read(body, "$.sameRevision")).isTrue();
    assertThat((Boolean) JsonPath.read(body, "$.baseIsLastGreen")).isFalse();
    assertThat((String) JsonPath.read(body, "$.rows[0].headFingerprintId")).isNotNull();
  }

  @Test
  void cmp002ACaseTwiceAsSlowInHeadIsADurationRegression() {
    String base = run("PASS", "main", Map.of("cart-total", passed("cart-total", 1200)));
    String head = run("PASS", "main", Map.of("cart-total", passed("cart-total", 2400)));
    String body = compare("?base=" + base + "&head=" + head, 200);
    List<String> slow = JsonPath.read(body, "$.rows[?(@.durationRegression == true)].caseKey");
    assertThat(slow).containsExactly("cart-total", "cart-total", "cart-total");
    assertThat((Integer) JsonPath.read(body, "$.durationRegressions")).isEqualTo(3);
    assertThat(JsonPath.read(body, "$.thresholds.regressionRatio").toString()).isEqualTo("1.5");
    assertThat((Integer) JsonPath.read(body, "$.thresholds.regressionMinDeltaMs")).isEqualTo(500);
  }

  @Test
  void lastGreenIsTheNewestPassedRunOfTheSameSuiteOnTheSameBranchBeforeHead() {
    String olderGreen = run("PASS", "main", Map.of());
    String newestGreen = run("PASS", "main", Map.of());
    run("PRODUCT_DEFECT", "main", Map.of("cart-total", cartTotalFailed()));
    run("PASS", "feature-x", Map.of());
    String head = run("PRODUCT_DEFECT", "main", Map.of("cart-total", cartTotalFailed()));
    run("PASS", "main", Map.of()); // queued after head: never its baseline
    String body = compare("?base=last-green&head=" + head, 200);
    assertThat((String) JsonPath.read(body, "$.base.id"))
        .isEqualTo(newestGreen)
        .isNotEqualTo(olderGreen);
    assertThat((Boolean) JsonPath.read(body, "$.baseIsLastGreen")).isTrue();
    assertThat((String) JsonPath.read(body, "$.base.branch")).isEqualTo("main");
    // cart-total's window is now P P F P F P on every engine (the run after head counts too:
    // the label is the case's current one), so it is FLAKY and head's failure is FLAKY_IN_HEAD,
    // not a NEW_FAILURE.
    assertThat(count(body, "NEW_FAILURE")).isZero();
    assertThat((List<String>) JsonPath.read(body, "$.rows[?(@.category=='FLAKY_IN_HEAD')].reason"))
        .containsExactly(
            "its history on chromium is labelled FLAKY",
            "its history on firefox is labelled FLAKY",
            "its history on webkit is labelled FLAKY");
    assertThat(
            (List<String>) JsonPath.read(body, "$.rows[?(@.category=='FLAKY_IN_HEAD')].headStatus"))
        .containsOnly("FAILED");
  }

  @Test
  void withoutAGreenRunOnTheBranchLastGreenIs404() {
    run("PASS", "other", Map.of());
    String head = run("PRODUCT_DEFECT", "main", Map.of("cart-total", cartTotalFailed()));
    String body = compare("?base=last-green&head=" + head, 404);
    assertThat(body).contains("no PASSED run of checkout-smoke on branch main");
  }

  @Test
  void anInfraErrorTargetIsNotComparableAndTheOtherEnginesStillCompare() {
    String base = run("PASS", "main", Map.of());
    String head = create("checkout-smoke", "PRODUCT_DEFECT", "main", ENGINES);
    finish(List.of("chromium", "webkit"), Map.of("cart-total", cartTotalFailed()));
    Claim firefox = claim("firefox");
    start(firefox);
    var infra =
        complete(
            firefox.attemptId(),
            firefox.token(),
            "k-infra",
            "INFRA_ERROR",
            "[]",
            "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"browserType.launch failed\"}");
    assertThat(infra.statusCode()).isEqualTo(200);
    String body = compare("?base=" + base + "&head=" + head, 200);
    List<String> notComparable =
        JsonPath.read(body, "$.rows[?(@.category=='NOT_COMPARABLE')].engine");
    assertThat(notComparable).hasSize(6).containsOnly("firefox");
    assertThat((List<String>) JsonPath.read(body, "$.rows[?(@.category=='NOT_COMPARABLE')].reason"))
        .containsOnly("the head target is INFRA_ERROR with no case results");
    assertThat(count(body, "NEW_FAILURE")).isEqualTo(2);
  }

  @Test
  void runsOfDifferentSuitesAre422AndBadQueriesAre400() {
    String checkout = run("PASS", "main", Map.of());
    String catalog = create("catalog-regression", "PASS", "main", List.of("chromium"));
    String body = compare("?base=" + checkout + "&head=" + catalog, 422);
    assertThat(body).contains("/problems/incomparable-runs");
    for (String query :
        List.of(
            "",
            "?base=" + checkout,
            "?head=" + checkout,
            "?base=last-green&head=last-green",
            "?base=nope&head=" + checkout,
            "?base=" + checkout + "&head=" + checkout + "&extra=1",
            "?base=" + checkout + "&base=" + checkout + "&head=" + checkout)) {
      assertThat(compare(query, 400)).as(query).contains("/problems/validation-failed");
    }
    compare("?base=" + UUID.randomUUID() + "&head=" + checkout, 404);
  }
}
