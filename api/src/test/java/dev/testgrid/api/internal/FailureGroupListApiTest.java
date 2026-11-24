package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.artifact.ArtifactKind;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PRD-07 FR-7 against Testcontainers PostgreSQL: GET /failure-groups (order, filters, keyset pages)
 * and the artifacts linked to each occurrence of a group.
 */
class FailureGroupListApiTest extends WorkerApiTestBase {

  private final HttpClient client = HttpClient.newHttpClient();

  private static String failed(String caseKey, String errorClass, String message) {
    return "{\"caseKey\":\""
        + caseKey
        + "\",\"status\":\"FAILED\",\"durationMs\":900,\"retryCount\":1,\"errorClass\":\""
        + errorClass
        + "\",\"errorMessage\":\""
        + message
        + "\",\"errorFrames\":[\"suites/checkout-smoke.spec.ts:<anonymous>\"]}";
  }

  private static final String DEFECT = "Error: expect(locator).toHaveText(expected) failed";
  private static final String DRIFT = "TimeoutError: locator.click: Timeout 5000ms exceeded.";

  private Claim run(String engine, Map<String, String> cases) {
    createRun("checkout-smoke", "PRODUCT_DEFECT", engine);
    Claim claim = claim(engine);
    start(claim);
    var done =
        complete(claim, "k-" + UUID.randomUUID(), "COMPLETED_WITH_RESULTS", checkoutResults(cases));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    return claim;
  }

  /** Three groups: the defect seen 3 times (chromium ×2, firefox), drift 2, launch fault 1. */
  private void seed() {
    run("chromium", Map.of("cart-total", failed("cart-total", "ASSERTION", DEFECT)));
    run("chromium", Map.of("cart-total", failed("cart-total", "ASSERTION", DEFECT)));
    run(
        "firefox",
        Map.of(
            "cart-total", failed("cart-total", "ASSERTION", DEFECT),
            "checkout-happy-path", failed("checkout-happy-path", "SELECTOR_NOT_FOUND", DRIFT),
            "card-declined", failed("card-declined", "SELECTOR_NOT_FOUND", DRIFT)));
    createRun("checkout-smoke", "PASS", "webkit");
    Claim launch = claim("webkit");
    start(launch);
    var infra =
        complete(
            launch.attemptId(),
            launch.token(),
            "k-launch",
            "INFRA_ERROR",
            "[]",
            "{\"errorClass\":\"BROWSER_LAUNCH\",\"message\":\"browserType.launch failed\"}");
    assertThat(infra.statusCode()).isEqualTo(200);
  }

  private String list(String query) {
    var response = get(publicPort, "/api/v1/failure-groups" + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  @Test
  void groupsAreListedByOccurrenceCountWithTheirSpread() {
    seed();
    String body = list("");
    assertThat((List<Integer>) JsonPath.read(body, "$.items[*].occurrenceCount"))
        .containsExactly(3, 2, 1);
    assertThat((List<String>) JsonPath.read(body, "$.items[*].category"))
        .containsExactly("PRODUCT_SUSPECTED", "TEST_BROKEN", "ENVIRONMENT");
    assertThat((List<String>) JsonPath.read(body, "$.items[0].engines"))
        .containsExactly("chromium", "firefox");
    assertThat((List<String>) JsonPath.read(body, "$.items[0].caseKeys"))
        .containsExactly("cart-total");
    assertThat((Integer) JsonPath.read(body, "$.items[0].runCount")).isEqualTo(3);
    assertThat((Boolean) JsonPath.read(body, "$.items[0].wholeTarget")).isFalse();
    assertThat((List<String>) JsonPath.read(body, "$.items[1].caseKeys"))
        .containsExactly("card-declined", "checkout-happy-path");
    assertThat((Boolean) JsonPath.read(body, "$.items[2].wholeTarget")).isTrue();
    assertThat((List<String>) JsonPath.read(body, "$.items[2].caseKeys")).isEmpty();
    assertThat((String) JsonPath.read(body, "$.items[0].links.self"))
        .startsWith("/api/v1/failure-groups/");
    assertThat((Object) JsonPath.read(body, "$.nextCursor")).isNull();
  }

  @Test
  void filtersByShownCategoryEngineAndLastSeen() throws Exception {
    seed();
    assertThat((List<String>) JsonPath.read(list("?category=TEST_BROKEN"), "$.items[*].category"))
        .containsExactly("TEST_BROKEN");
    assertThat(
            (List<String>)
                JsonPath.read(list("?category=TEST_BROKEN,ENVIRONMENT"), "$.items[*].category"))
        .containsExactly("TEST_BROKEN", "ENVIRONMENT");
    assertThat(
            (List<String>)
                JsonPath.read(
                    list("?category=TIMEOUT&category=ENVIRONMENT"), "$.items[*].category"))
        .containsExactly("ENVIRONMENT");
    assertThat((List<Integer>) JsonPath.read(list("?engine=firefox"), "$.items[*].occurrenceCount"))
        .containsExactly(3, 2);
    assertThat((List<?>) JsonPath.read(list("?engine=webkit"), "$.items")).hasSize(1);
    assertThat((List<?>) JsonPath.read(list("?to=2000-01-01T00:00:00Z"), "$.items")).isEmpty();
    assertThat((List<?>) JsonPath.read(list("?from=2000-01-01T00:00:00Z"), "$.items")).hasSize(3);

    // The override is what the list filters on, not the rule's label.
    String id = JsonPath.read(list("?category=TEST_BROKEN"), "$.items[0].id");
    var put =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + publicPort
                            + "/api/v1/failure-groups/"
                            + id
                            + "/override"))
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"category\":\"PRODUCT_SUSPECTED\",\"reason\":\"checked by hand\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(put.statusCode()).isEqualTo(200);
    assertThat((List<?>) JsonPath.read(list("?category=TEST_BROKEN"), "$.items")).isEmpty();
    String product = list("?category=PRODUCT_SUSPECTED");
    assertThat((List<Boolean>) JsonPath.read(product, "$.items[*].overridden"))
        .containsExactly(false, true);
    assertThat((String) JsonPath.read(product, "$.items[1].ruleCategory")).isEqualTo("TEST_BROKEN");
  }

  @Test
  void keysetPagesNeverRepeatOrSkipAGroup() {
    seed();
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String body = list("?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
      seen.addAll(JsonPath.read(body, "$.items[*].id"));
      cursor = JsonPath.read(body, "$.nextCursor");
      pages++;
    } while (cursor != null);
    assertThat(pages).isEqualTo(3);
    assertThat(seen).doesNotHaveDuplicates().hasSize(3);
    assertThat(seen).isEqualTo(JsonPath.read(list(""), "$.items[*].id"));
  }

  @Test
  void badListQueriesAre400AndAnotherProjectIs404() {
    for (String query :
        List.of(
            "?category=PRODUCT_DEFECT",
            "?engine=edge",
            "?from=yesterday",
            "?from=2026-11-24T00:00:00Z&to=2026-11-23T00:00:00Z",
            "?limit=0",
            "?limit=101",
            "?cursor=not-a-cursor",
            "?projectId=nope",
            "?status=FAILED",
            "?engine=chromium&engine=firefox")) {
      var response = get(publicPort, "/api/v1/failure-groups" + query);
      assertThat(response.statusCode()).as(query + ": " + response.body()).isEqualTo(400);
    }
    assertThat(
            get(publicPort, "/api/v1/failure-groups?projectId=" + UUID.randomUUID()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void eachOccurrenceCarriesItsCasesScreenshotAndTraceAndTheAttemptLog() throws Exception {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    upload(claim, ArtifactKind.SCREENSHOT, "cart-total", ArtifactUploadTest.png(1500));
    upload(claim, ArtifactKind.TRACE, "cart-total", ArtifactUploadTest.zip(800));
    upload(claim, ArtifactKind.SCREENSHOT, "checkout-happy-path", ArtifactUploadTest.png(1200));
    upload(claim, ArtifactKind.LOG, null, "Running 6 tests\n".getBytes());
    var done =
        complete(
            claim,
            "k-art",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(
                Map.of(
                    "cart-total", failed("cart-total", "ASSERTION", DEFECT),
                    "checkout-happy-path",
                        failed("checkout-happy-path", "SELECTOR_NOT_FOUND", DRIFT))));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    String id = JsonPath.read(list("?category=UNKNOWN"), "$.items[0].id");
    String detail = get(publicPort, "/api/v1/failure-groups/" + id).body();
    assertThat((List<String>) JsonPath.read(detail, "$.occurrences[0].artifacts[*].kind"))
        .containsExactlyInAnyOrder("SCREENSHOT", "TRACE", "LOG");
    assertThat((List<String>) JsonPath.read(detail, "$.occurrences[0].artifacts[*].state"))
        .containsOnly("AVAILABLE");
    // The other case's screenshot belongs to the other group.
    assertThat(
            (List<String>)
                JsonPath.read(
                    detail, "$.occurrences[0].artifacts[?(@.kind=='SCREENSHOT')].caseKey"))
        .containsExactly("cart-total");
    assertThat(detail).doesNotContain("storageKey").doesNotContain("storage_key");
  }

  private void upload(Claim claim, ArtifactKind kind, String caseKey, byte[] content)
      throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + internalPort()
                        + "/internal/v1/attempts/"
                        + claim.attemptId()
                        + "/artifacts?kind="
                        + kind
                        + (caseKey == null ? "" : "&caseKey=" + caseKey)))
            .header("Authorization", "Bearer " + TOKEN)
            .header("Content-Type", "multipart/form-data; boundary=" + ArtifactUploadTest.BOUNDARY)
            .header(ArtifactUploadController.LEASE_HEADER, claim.token())
            .POST(HttpRequest.BodyPublishers.ofByteArray(ArtifactUploadTest.multipart(content)))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }
}
