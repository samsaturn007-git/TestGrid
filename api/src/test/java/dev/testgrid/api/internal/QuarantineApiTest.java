package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.quarantine.QuarantineService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PRD-08 FR-3 through the real API against Testcontainers PostgreSQL: create, revoke, the cap,
 * expiry and AUTO / SUGGEST modes, and their effect on the gate (QUA-001, QUA-002).
 */
class QuarantineApiTest extends WorkerApiTestBase {

  private static final String LIST = "/api/v1/projects/" + LocalProject.ID + "/quarantines";
  private static final String POLICY = "/api/v1/projects/" + LocalProject.ID + "/quality-policy";

  @Autowired QuarantineService quarantineService;

  private static String cartTotalFailed() {
    return "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":1300,\"retryCount\":1,"
        + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Error: expect(received).toBe(expected)\"}";
  }

  private static String body(String caseKey, String engine, String expiresAt) {
    return "{\"caseKey\":\""
        + caseKey
        + "\","
        + (engine == null ? "" : "\"engine\":\"" + engine + "\",")
        + "\"reason\":\"known flaky, tracked in TG-12\",\"expiresAt\":\""
        + expiresAt
        + "\"}";
  }

  private static String inDays(int days) {
    return Instant.now().plus(Duration.ofDays(days)).toString();
  }

  private HttpResponse201 create(String caseKey, String engine, String expiresAt) {
    var response = send("POST", LIST, body(caseKey, engine, expiresAt));
    return new HttpResponse201(response.statusCode(), response.body());
  }

  private record HttpResponse201(int status, String body) {
    String id() {
      return JsonPath.read(body, "$.id");
    }
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

  private void setPolicy(String from, String to) {
    var response =
        send(
            "PUT",
            POLICY,
            "{\"reason\":\"test\",\"policy\":" + DEFAULT_POLICY.replace(from, to) + "}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private long audits(String action) {
    return count("SELECT count(*) FROM audit_events WHERE action = '" + action + "'");
  }

  // ---- create / revoke ---------------------------------------------------------------------

  @Test
  void aQuarantineIsCreatedActiveAndAudited() {
    var created = create("cart-total", "chromium", inDays(7));
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat((String) JsonPath.read(created.body(), "$.state")).isEqualTo("ACTIVE");
    assertThat((String) JsonPath.read(created.body(), "$.source")).isEqualTo("MANUAL");
    assertThat((String) JsonPath.read(created.body(), "$.createdBy")).isEqualTo("local");
    assertThat((String) JsonPath.read(created.body(), "$.engine")).isEqualTo("chromium");
    assertThat((Object) JsonPath.read(created.body(), "$.revokedAt")).isNull();
    assertThat(audits("QUARANTINE_CREATED")).isEqualTo(1);
    String metadata =
        jdbc.sql("SELECT metadata_redacted::text FROM audit_events WHERE object_id = :id")
            .param("id", UUID.fromString(created.id()))
            .query(String.class)
            .single();
    assertThat(metadata).contains("cart-total").contains("TG-12").contains("MANUAL");

    String list = get(publicPort, LIST).body();
    assertThat((Integer) JsonPath.read(list, "$.activeCount")).isEqualTo(1);
    assertThat((Integer) JsonPath.read(list, "$.maxActive")).isEqualTo(5);
    assertThat((String) JsonPath.read(list, "$.autoMode")).isEqualTo("SUGGEST");
    assertThat((String) JsonPath.read(list, "$.serverNow")).isNotNull();
    assertThat((List<String>) JsonPath.read(list, "$.items[*].id")).containsExactly(created.id());
  }

  @Test
  void invalidQuarantinesAreRejected() {
    for (String bad :
        List.of(
            body("no-such-case", null, inDays(1)),
            body("cart-total", null, Instant.now().minusSeconds(60).toString()),
            body("cart-total", null, inDays(31)),
            body("cart-total", "safari", inDays(1)),
            body("Cart_Total", null, inDays(1)),
            body("cart-total", null, "next tuesday"),
            body("cart-total", null, inDays(1)).replace("known flaky, tracked in TG-12", "  "),
            body("cart-total", null, inDays(1)).replace("}", ",\"source\":\"AUTO\"}"),
            body("cart-total", null, inDays(1)).replace("}", ",\"createdBy\":\"someone\"}"),
            "{\"caseKey\":\"cart-total\",\"reason\":\"x\"}")) {
      var response = send("POST", LIST, bad);
      assertThat(response.statusCode()).as(bad + " -> " + response.body()).isEqualTo(400);
    }
    assertThat(count("SELECT count(*) FROM quarantines")).isZero();
    assertThat(get(publicPort, LIST + "?state=GONE").statusCode()).isEqualTo(400);
    assertThat(get(publicPort, LIST + "?sate=ACTIVE").statusCode()).isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/api/v1/projects/" + UUID.randomUUID() + "/quarantines",
                    body("cart-total", null, inDays(1)))
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void anActiveQuarantineCannotBeDuplicated() {
    assertThat(create("cart-total", null, inDays(3)).status()).isEqualTo(201);
    var same = create("cart-total", "firefox", inDays(3));
    assertThat(same.status()).as(same.body()).isEqualTo(409);
    assertThat(same.body()).contains("quarantine-conflict").contains("already quarantined");
    assertThat(create("card-declined", "firefox", inDays(3)).status()).isEqualTo(201);
    assertThat(create("card-declined", "webkit", inDays(3)).status()).isEqualTo(201);
    assertThat(create("card-declined", "webkit", inDays(3)).status()).isEqualTo(409);
  }

  @Test
  void theCapLimitsActiveQuarantines() {
    setPolicy("\"maxActive\":5", "\"maxActive\":1");
    assertThat(create("cart-total", null, inDays(3)).status()).isEqualTo(201);
    var over = create("card-declined", null, inDays(3));
    assertThat(over.status()).as(over.body()).isEqualTo(409);
    assertThat(over.body()).contains("at most 1");
    assertThat(count("SELECT count(*) FROM quarantines")).isEqualTo(1);
  }

  @Test
  void theCapHoldsUnderConcurrentRequests() throws Exception {
    setPolicy("\"maxActive\":5", "\"maxActive\":2");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(CHECKOUT_CASES.size())) {
      var latch = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Future<Integer>> statuses =
          CHECKOUT_CASES.stream()
              .map(caseKey ->
                  pool.submit(() -> {
                    latch.await();
                    return create(caseKey, null, inDays(3)).status();
                  }))
              .toList();
      latch.countDown();
      List<Integer> codes = new java.util.ArrayList<>();
      for (var f : statuses) {
        codes.add(f.get());
      }
      assertThat(codes).filteredOn(c -> c == 201).hasSize(2);
      assertThat(codes).filteredOn(c -> c == 409).hasSize(CHECKOUT_CASES.size() - 2);
    }
    assertThat(count("SELECT count(*) FROM quarantines")).isEqualTo(2);
    assertThat(audits("QUARANTINE_CREATED")).isEqualTo(2);
  }

  @Test
  void revokingEndsAQuarantineOnceAndKeepsTheRow() {
    String id = create("cart-total", null, inDays(3)).id();
    var revoked = send("DELETE", "/api/v1/quarantines/" + id, null);
    assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
    assertThat((String) JsonPath.read(revoked.body(), "$.state")).isEqualTo("REVOKED");
    assertThat((String) JsonPath.read(revoked.body(), "$.revokedAt")).isNotNull();
    var again = send("DELETE", "/api/v1/quarantines/" + id, null);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(audits("QUARANTINE_REVOKED")).isEqualTo(1);
    assertThat(send("DELETE", "/api/v1/quarantines/" + UUID.randomUUID(), null).statusCode())
        .isEqualTo(404);
    // A revoked case can be quarantined again.
    assertThat(create("cart-total", null, inDays(3)).status()).isEqualTo(201);
    assertThat(
            (List<String>)
                JsonPath.read(get(publicPort, LIST + "?state=REVOKED").body(), "$.items[*].id"))
        .containsExactly(id);
  }

  @Test
  void aQuarantineRowCannotBeEditedOrDeletedInTheDatabase() {
    create("cart-total", null, inDays(3));
    assertThat(
            catchThrowable(() ->
                jdbc.sql("UPDATE quarantines SET expires_at = expires_at + interval '1 day'")
                    .update()))
        .hasMessageContaining("immutable");
    assertThat(catchThrowable(() -> jdbc.sql("DELETE FROM quarantines").update()))
        .hasMessageContaining("never deleted");
    assertThat(
            catchThrowable(() ->
                jdbc.sql(
                        "INSERT INTO quarantines (project_id, case_key, reason, source, created_by,"
                            + " expires_at) VALUES (:p, 'cart-total', 'x', 'MANUAL', 'local',"
                            + " now() + interval '31 days')")
                    .param("p", LocalProject.ID)
                    .update()))
        .hasMessageContaining("check");
  }

  // ---- the gate ----------------------------------------------------------------------------

  @Test
  void qua001AndQua002QuarantineFlipsFailToPassUntilItExpires() throws InterruptedException {
    assertThat(
            (String)
                JsonPath.read(
                    quality(finishChromium(Map.of("cart-total", cartTotalFailed()))), "$.outcome"))
        .isEqualTo("FAIL");

    String expiresAt = Instant.now().plusSeconds(3).toString();
    var quarantine = create("cart-total", "chromium", expiresAt);
    assertThat(quarantine.status()).as(quarantine.body()).isEqualTo(201);

    String excused = quality(finishChromium(Map.of("cart-total", cartTotalFailed())));
    assertThat((String) JsonPath.read(excused, "$.outcome")).isEqualTo("PASS");
    assertThat(
            (List<String>)
                JsonPath.read(excused, "$.rules[?(@.ruleId=='critical-cases')].evidence[*].status"))
        .containsExactly("QUARANTINED_FAIL");
    assertThat(
            (List<Integer>)
                JsonPath.read(excused, "$.rules[?(@.ruleId=='quarantine-honored')].observed"))
        .containsExactly(1);
    assertThat(
            (List<String>)
                JsonPath.read(
                    excused, "$.rules[?(@.ruleId=='quarantine-honored')].evidence[*].detail"))
        .singleElement()
        .asString()
        .contains(quarantine.id());

    assertThat(quarantineService.recordExpiries()).isZero();
    Instant deadline = Instant.parse(expiresAt).plusMillis(500);
    while (Instant.now().isBefore(deadline)) {
      Thread.sleep(100);
    }

    String back = quality(finishChromium(Map.of("cart-total", cartTotalFailed())));
    assertThat((String) JsonPath.read(back, "$.outcome")).isEqualTo("FAIL");
    assertThat(
            (List<Integer>)
                JsonPath.read(back, "$.rules[?(@.ruleId=='quarantine-honored')].observed"))
        .containsExactly(0);

    assertThat(quarantineService.recordExpiries()).isEqualTo(1);
    assertThat(quarantineService.recordExpiries()).isZero();
    assertThat(audits("QUARANTINE_CREATED")).isEqualTo(1);
    assertThat(audits("QUARANTINE_EXPIRED")).isEqualTo(1);
    assertThat(
            one(
                "SELECT actor FROM audit_events WHERE action = 'QUARANTINE_EXPIRED' AND object_id = :id",
                quarantine.id()))
        .isEqualTo("system");
    assertThat(
            (List<String>)
                JsonPath.read(get(publicPort, LIST + "?state=EXPIRED").body(), "$.items[*].id"))
        .containsExactly(quarantine.id());
  }

  @Test
  void aPolicyThatDoesNotHonourQuarantinesStillFails() {
    setPolicy("\"honor\":true", "\"honor\":false");
    create("cart-total", null, inDays(3));
    assertThat(
            (String)
                JsonPath.read(
                    quality(finishChromium(Map.of("cart-total", cartTotalFailed()))), "$.outcome"))
        .isEqualTo("FAIL");
  }

  // ---- AUTO and SUGGEST --------------------------------------------------------------------

  /** Alternating results make cart-total FLAKY on chromium (contracts §8.3). */
  private String alternate(int index) {
    String runId =
        finishChromium(index % 2 == 0 ? Map.of() : Map.of("cart-total", cartTotalFailed()));
    quality(runId);
    return runId;
  }

  @Test
  void suggestModeListsFlakyCasesWithoutAQuarantine() {
    for (int i = 0; i < 5; i++) {
      alternate(i);
    }
    String list = get(publicPort, LIST).body();
    assertThat((List<String>) JsonPath.read(list, "$.suggestions[*].caseKey"))
        .containsExactly("cart-total");
    assertThat((List<String>) JsonPath.read(list, "$.suggestions[*].engine"))
        .containsExactly("chromium");
    create("cart-total", "chromium", inDays(3));
    assertThat((List<?>) JsonPath.read(get(publicPort, LIST).body(), "$.suggestions")).isEmpty();
    assertThat(count("SELECT count(*) FROM quarantines WHERE source = 'AUTO'")).isZero();
  }

  @Test
  void autoModeQuarantinesAFlakyCaseOnlyFromTenResultsAndOnlyForLaterRuns() {
    setPolicy("\"SUGGEST\"", "\"AUTO\"");
    for (int i = 0; i < 9; i++) {
      alternate(i);
    }
    // FLAKY since the fifth result, but only 9 observations.
    assertThat(count("SELECT count(*) FROM quarantines")).isZero();
    String tenth = alternate(9);
    assertThat((String) JsonPath.read(quality(tenth), "$.outcome"))
        .as("the run that triggered it is not excused")
        .isEqualTo("FAIL");
    Map<String, Object> auto =
        jdbc.sql(
                "SELECT case_key, engine, source, created_by,"
                    + " round(extract(epoch FROM expires_at - created_at) / 86400) AS days"
                    + " FROM quarantines")
            .query()
            .singleRow();
    assertThat(auto)
        .containsEntry("case_key", "cart-total")
        .containsEntry("engine", "chromium")
        .containsEntry("source", "AUTO")
        .containsEntry("created_by", "system");
    assertThat(((Number) auto.get("days")).intValue()).isEqualTo(7);
    assertThat(audits("QUARANTINE_CREATED")).isEqualTo(1);

    // Re-reading the same run does not quarantine again; the next failure is excused.
    quality(tenth);
    String eleventh = quality(finishChromium(Map.of("cart-total", cartTotalFailed())));
    assertThat((String) JsonPath.read(eleventh, "$.outcome")).isEqualTo("PASS");
    assertThat(count("SELECT count(*) FROM quarantines")).isEqualTo(1);
  }
}
