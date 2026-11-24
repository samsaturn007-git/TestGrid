package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PRD-07 FR-1–FR-3 end to end through the real completion path and the public API, against
 * Testcontainers PostgreSQL: grouping, the rule label with its evidence, and the audited override.
 */
class FailureGroupApiTest extends WorkerApiTestBase {

  private final HttpClient http = HttpClient.newHttpClient();

  private static final String SPEC_FRAME = "[\"suites/checkout-smoke.spec.ts:<anonymous>\"]";

  private static String defect(String received) {
    return "Error: expect(locator).toHaveText(expected) failed\\n\\nLocator:  getByTestId('cart-total')"
        + "\\nExpected: \\\"$80.99\\\"\\nReceived: \\\""
        + received
        + "\\\"\\nTimeout:  5000ms\\n\\nCall log:\\n  - 14 × locator resolved";
  }

  private static String failedCase(
      String caseKey, String status, int retries, String errorClass, String message) {
    return "{\"caseKey\":\""
        + caseKey
        + "\",\"status\":\""
        + status
        + "\",\"durationMs\":900,\"retryCount\":"
        + retries
        + ",\"errorClass\":\""
        + errorClass
        + "\",\"errorMessage\":\""
        + message
        + "\",\"errorFrames\":"
        + SPEC_FRAME
        + "}";
  }

  /** One run of checkout-smoke on one engine, completed with these overrides. */
  private Claim run(String fixture, Map<String, String> cases) {
    createRun("checkout-smoke", fixture, "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var done =
        complete(claim, "k-" + UUID.randomUUID(), "COMPLETED_WITH_RESULTS", checkoutResults(cases));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    return claim;
  }

  private Claim productDefectRun(String received) {
    return run(
        "PRODUCT_DEFECT",
        Map.of("cart-total", failedCase("cart-total", "FAILED", 1, "ASSERTION", defect(received))));
  }

  private UUID groupOf(String caseKey, Claim claim) {
    return jdbc.sql(
            "SELECT fingerprint_id FROM case_results WHERE attempt_id = :a AND case_key = :k")
        .param("a", UUID.fromString(claim.attemptId()))
        .param("k", caseKey)
        .query(UUID.class)
        .single();
  }

  private HttpResponse<String> group(UUID id) {
    return get(publicPort, "/api/v1/failure-groups/" + id);
  }

  private HttpResponse<String> override(UUID id, String body) {
    try {
      return http.send(
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + publicPort
                          + "/api/v1/failure-groups/"
                          + id
                          + "/override"))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void int001ARepeatedAssertionIsOneGroupThatBecomesProductSuspected() {
    Claim first = productDefectRun("$82.99");
    UUID id = groupOf("cart-total", first);
    var once = group(id);
    assertThat(once.statusCode()).isEqualTo(200);
    // One attempt is not yet a reproduction (R5 needs two).
    assertThat((String) JsonPath.read(once.body(), "$.category")).isEqualTo("UNKNOWN");
    assertThat((String) JsonPath.read(once.body(), "$.ruleId")).isEqualTo("R6");

    Claim second = productDefectRun("$83.49");
    assertThat(groupOf("cart-total", second)).as("different numbers, same group").isEqualTo(id);
    var twice = group(id).body();
    assertThat((String) JsonPath.read(twice, "$.category")).isEqualTo("PRODUCT_SUSPECTED");
    assertThat((String) JsonPath.read(twice, "$.ruleCategory")).isEqualTo("PRODUCT_SUSPECTED");
    assertThat((String) JsonPath.read(twice, "$.ruleId")).isEqualTo("R5");
    assertThat((Integer) JsonPath.read(twice, "$.occurrenceCount")).isEqualTo(2);
    assertThat(JsonPath.read(twice, "$.confidence").toString()).isEqualTo("0.72");
    assertThat((String) JsonPath.read(twice, "$.errorClass")).isEqualTo("ASSERTION");
    assertThat((String) JsonPath.read(twice, "$.normalisedMessage"))
        .contains("Received: \"$<N>.<N>\"")
        .doesNotContain("Call log");
    assertThat((List<?>) JsonPath.read(twice, "$.evidence.rulesTried")).hasSize(5);
    assertThat((Integer) JsonPath.read(twice, "$.evidence.facts.attempts")).isEqualTo(2);
    assertThat((List<String>) JsonPath.read(twice, "$.occurrences[*].caseKey"))
        .containsExactly("cart-total", "cart-total");
    assertThat((List<String>) JsonPath.read(twice, "$.occurrences[*].errorMessage"))
        .anySatisfy(m -> assertThat(m).contains("$83.49"))
        .anySatisfy(m -> assertThat(m).contains("$82.99"));
    assertThat((Object) JsonPath.read(twice, "$.override")).isNull();
    // Passing cases are never grouped.
    assertThat(count("SELECT count(*) FROM case_results WHERE fingerprint_id IS NOT NULL"))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM failure_fingerprints")).isEqualTo(1);
  }

  @Test
  void int002DifferentFailuresAreDifferentGroupsWithTheirOwnLabel() {
    String drift =
        "TimeoutError: locator.click: Timeout 5000ms exceeded.\\nCall log:\\n  - waiting";
    Claim selector =
        run(
            "SELECTOR_DRIFT",
            Map.of(
                "checkout-happy-path",
                failedCase("checkout-happy-path", "FAILED", 1, "SELECTOR_NOT_FOUND", drift),
                "card-declined",
                failedCase("card-declined", "FAILED", 1, "SELECTOR_NOT_FOUND", drift)));
    UUID selectorGroup = groupOf("checkout-happy-path", selector);
    assertThat(groupOf("card-declined", selector)).isEqualTo(selectorGroup);
    assertThat((String) JsonPath.read(group(selectorGroup).body(), "$.category"))
        .isEqualTo("TEST_BROKEN");
    assertThat((Integer) JsonPath.read(group(selectorGroup).body(), "$.occurrenceCount"))
        .isEqualTo(2);

    Claim flaky =
        run(
            "AUTOMATION_FLAKE",
            Map.of(
                "checkout-happy-path",
                failedCase(
                    "checkout-happy-path",
                    "FLAKY",
                    1,
                    "ASSERTION",
                    "Error: expect(locator).toBeVisible() failed\\nLocator: getByTestId('order-confirmation-banner')")));
    UUID flakyGroup = groupOf("checkout-happy-path", flaky);
    assertThat((String) JsonPath.read(group(flakyGroup).body(), "$.category"))
        .isEqualTo("AUTOMATION_FLAKE");

    Claim timeout =
        run(
            "TIMEOUT",
            Map.of(
                "checkout-happy-path",
                "{\"caseKey\":\"checkout-happy-path\",\"status\":\"TIMED_OUT\",\"durationMs\":20000,"
                    + "\"retryCount\":1,\"errorClass\":\"TEST_TIMEOUT\","
                    + "\"errorMessage\":\"Test timeout of 20000ms exceeded.\"}"));
    UUID timeoutGroup = groupOf("checkout-happy-path", timeout);
    assertThat((String) JsonPath.read(group(timeoutGroup).body(), "$.category"))
        .isEqualTo("TIMEOUT");

    assertThat(List.of(selectorGroup, flakyGroup, timeoutGroup)).doesNotHaveDuplicates();
  }

  @Test
  void int002ABrowserLaunchFaultIsAnAttemptLevelEnvironmentGroupNeverAProductDefect() {
    UUID id = null;
    for (int i = 0; i < 3; i++) {
      createRun("checkout-smoke", "PASS", "chromium");
      Claim claim = claim("chromium");
      start(claim);
      var done =
          complete(
              claim.attemptId(),
              claim.token(),
              "k-launch-" + i,
              "INFRA_ERROR",
              "[]",
              "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"browserType.launch: Executable"
                  + " doesn't exist at /ms-playwright/chromium-12"
                  + i
                  + "/chrome-linux/chrome\"}");
      assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
      UUID linked =
          jdbc.sql("SELECT fingerprint_id FROM attempts WHERE id = :a")
              .param("a", UUID.fromString(claim.attemptId()))
              .query(UUID.class)
              .single();
      assertThat(linked).isNotNull();
      assertThat(id == null || id.equals(linked)).as("one group for all three").isTrue();
      id = linked;
    }
    String body = group(id).body();
    assertThat((String) JsonPath.read(body, "$.category")).isEqualTo("ENVIRONMENT");
    assertThat((String) JsonPath.read(body, "$.ruleId")).isEqualTo("R3");
    assertThat((Integer) JsonPath.read(body, "$.evidence.facts.attempts")).isEqualTo(3);
    assertThat((List<Object>) JsonPath.read(body, "$.occurrences[*].caseKey"))
        .containsExactly(null, null, null);
    assertThat((List<String>) JsonPath.read(body, "$.occurrences[*].status"))
        .containsOnly("INFRA_ERROR");
    assertThat(
            count("SELECT count(*) FROM failure_fingerprints WHERE category = 'PRODUCT_SUSPECTED'"))
        .isZero();
  }

  @Test
  void aSecretIsRedactedBeforeTheMessageIsNormalised() {
    Claim claim =
        run(
            "PRODUCT_DEFECT",
            Map.of(
                "cart-total",
                failedCase(
                    "cart-total",
                    "FAILED",
                    0,
                    "ASSERTION",
                    "\\u001b[31mexpect failed, Authorization: Bearer abc123def456\\u001b[39m")));
    String body = group(groupOf("cart-total", claim)).body();
    String sample = JsonPath.read(body, "$.normalisedMessage");
    assertThat(sample).contains("[REDACTED]").doesNotContain("abc").doesNotContain("[31m");
    String stored =
        jdbc.sql("SELECT error_message_redacted FROM case_results WHERE case_key = 'cart-total'")
            .query(String.class)
            .single();
    assertThat(stored).doesNotContain("abc123").doesNotContain("[31m").doesNotContain("\u001b");
  }

  @Test
  void int003AnOverrideChangesTheCategoryIsAuditedAndSurvivesNewOccurrences() {
    productDefectRun("$82.99");
    Claim second = productDefectRun("$83.49");
    UUID id = groupOf("cart-total", second);

    var put =
        override(
            id,
            "{\"category\":\"TEST_BROKEN\",\"reason\":\"The PRODUCT_DEFECT fixture injects this on"
                + " purpose. Bearer abc123def456\"}");
    assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
    assertThat((String) JsonPath.read(put.body(), "$.category")).isEqualTo("TEST_BROKEN");
    assertThat((String) JsonPath.read(put.body(), "$.ruleCategory")).isEqualTo("PRODUCT_SUSPECTED");
    assertThat((String) JsonPath.read(put.body(), "$.override.by")).isEqualTo("local");
    assertThat((String) JsonPath.read(put.body(), "$.override.reason"))
        .contains("injects this on purpose")
        .contains("[REDACTED]")
        .doesNotContain("abc123");
    assertThat((Object) JsonPath.read(put.body(), "$.override.at")).isNotNull();

    assertThat(count("SELECT count(*) FROM audit_events")).isEqualTo(1);
    var audit =
        jdbc.sql(
                "SELECT actor, action, object_type, object_id, metadata_redacted->>'from' AS f,"
                    + " metadata_redacted->>'to' AS t, metadata_redacted->>'ruleId' AS r,"
                    + " metadata_redacted->>'reason' AS why FROM audit_events")
            .query((rs, i) ->
                List.of(
                    rs.getString("actor"),
                    rs.getString("action"),
                    rs.getString("object_type"),
                    rs.getString("object_id"),
                    rs.getString("f"),
                    rs.getString("t"),
                    rs.getString("r"),
                    rs.getString("why")))
            .single();
    assertThat(audit.subList(0, 7))
        .containsExactly(
            "local",
            "FAILURE_CATEGORY_OVERRIDDEN",
            "failure_fingerprint",
            id.toString(),
            "PRODUCT_SUSPECTED",
            "TEST_BROKEN",
            "R5");
    assertThat(audit.get(7)).doesNotContain("abc123");
    assertThat((List<String>) JsonPath.read(group(id).body(), "$.audit[*].action"))
        .containsExactly("FAILURE_CATEGORY_OVERRIDDEN");

    // Sending the same override again changes nothing and is not audited twice.
    var again =
        override(
            id,
            "{\"category\":\"TEST_BROKEN\",\"reason\":\"The PRODUCT_DEFECT fixture injects this on purpose. Bearer abc123def456\"}");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(count("SELECT count(*) FROM audit_events")).isEqualTo(1);

    // A new occurrence relabels by rule, but the override stays in front for this fingerprint.
    productDefectRun("$84.00");
    String after = group(id).body();
    assertThat((Integer) JsonPath.read(after, "$.occurrenceCount")).isEqualTo(3);
    assertThat((String) JsonPath.read(after, "$.ruleCategory")).isEqualTo("PRODUCT_SUSPECTED");
    assertThat(JsonPath.read(after, "$.confidence").toString()).isEqualTo("0.74");
    assertThat((String) JsonPath.read(after, "$.category")).isEqualTo("TEST_BROKEN");

    // Audit rows are append-only.
    assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET actor = 'someone'").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void badOverridesAre400AndUnknownGroupsAre404() {
    Claim claim = productDefectRun("$82.99");
    UUID id = groupOf("cart-total", claim);
    Map<String, String> bad =
        Map.of(
            "unknown category", "{\"category\":\"PRODUCT_DEFECT\",\"reason\":\"x\"}",
            "blank reason", "{\"category\":\"TEST_BROKEN\",\"reason\":\"   \"}",
            "missing reason", "{\"category\":\"TEST_BROKEN\"}",
            "long reason", "{\"category\":\"TEST_BROKEN\",\"reason\":\"" + "x".repeat(501) + "\"}",
            "unknown field", "{\"category\":\"TEST_BROKEN\",\"reason\":\"x\",\"by\":\"admin\"}",
            "number for an enum", "{\"category\":1,\"reason\":\"x\"}");
    bad.forEach((why, body) -> {
      var response = override(id, body);
      assertThat(response.statusCode()).as(why + ": " + response.body()).isEqualTo(400);
      assertThat(response.body()).contains("/problems/validation-failed");
    });
    assertThat(count("SELECT count(*) FROM audit_events")).isZero();
    assertThat(
            count("SELECT count(*) FROM failure_fingerprints WHERE override_category IS NOT NULL"))
        .isZero();

    UUID unknown = UUID.randomUUID();
    assertThat(group(unknown).statusCode()).isEqualTo(404);
    assertThat(override(unknown, "{\"category\":\"TEST_BROKEN\",\"reason\":\"x\"}").statusCode())
        .isEqualTo(404);
  }

  @Test
  void aCaseResultKeepsItsGroupForever() {
    Claim claim = productDefectRun("$82.99");
    Claim other =
        run(
            "SELECTOR_DRIFT",
            Map.of(
                "card-declined",
                failedCase(
                    "card-declined", "FAILED", 1, "SELECTOR_NOT_FOUND", "locator.click: x")));
    UUID otherGroup = groupOf("card-declined", other);
    assertThatThrownBy(() ->
            jdbc.sql(
                    "UPDATE case_results SET fingerprint_id = :g WHERE attempt_id = :a AND"
                        + " case_key = 'cart-total'")
                .param("g", otherGroup)
                .param("a", UUID.fromString(claim.attemptId()))
                .update())
        .hasMessageContaining("immutable");
  }
}
