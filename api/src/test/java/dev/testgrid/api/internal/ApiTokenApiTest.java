package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.project.LocalProject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRD-08 FR-5, CONFLICT_REGISTER N33 through the real API: CI runs need the CI token, a bad token
 * is 401 everywhere (CI-004), scopes are enforced, and the dashboard's anonymous calls keep
 * working. Every token here is a fake test value.
 */
class ApiTokenApiTest extends WorkerApiTestBase {

  private static final String CI = "test-only-ci-token-0123456789abcdef0123456789";
  private static final String READER = "test-only-reader-token-0123456789abcdef01234";
  private static final String REVOKED = "test-only-revoked-token-0123456789abcdef0123";
  private static final String EXPIRED = "test-only-expired-token-0123456789abcdef0123";
  private static final String RUNS = "/api/v1/projects/" + LocalProject.ID + "/runs";

  @BeforeEach
  void tokens() {
    jdbc.sql("DELETE FROM api_tokens").update();
    insert("ci", CI, "{runs:write,runs:read,quality:read}", "NULL", "NULL");
    insert("reader", READER, "{runs:read}", "NULL", "NULL");
    insert("revoked", REVOKED, "{runs:write,runs:read,quality:read}", "now()", "NULL");
    insert(
        "expired",
        EXPIRED,
        "{runs:write,runs:read,quality:read}",
        "NULL",
        "now() - interval '1 second'");
  }

  private void insert(
      String name, String token, String scopes, String revokedAt, String expiresAt) {
    jdbc.sql(
            "INSERT INTO api_tokens (name, token_hash, scopes, revoked_at, expires_at)"
                + " VALUES (:n, :h, CAST(:s AS text[]), "
                + revokedAt
                + ", "
                + expiresAt
                + ")")
        .param("n", name)
        .param("h", Hashing.sha256(token.getBytes(StandardCharsets.UTF_8)))
        .param("s", scopes)
        .update();
  }

  private HttpResponse<String> call(String method, String path, String body, String authorization) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String runBody(String trigger) {
    return "{\"suiteKey\":\"checkout-smoke\",\"targets\":[{\"executor\":\"LOCAL\",\"engine\":"
        + "\"chromium\"}],\"trigger\":\""
        + trigger
        + "\",\"idempotencyKey\":\""
        + UUID.randomUUID()
        + "\"}";
  }

  @Test
  void aCiRunNeedsTheCiToken() {
    var anonymous = call("POST", RUNS, runBody("CI"), null);
    assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(401);
    assertThat(anonymous.body()).contains("unauthorized").contains("runs:write");
    assertThat(count("SELECT count(*) FROM runs")).isZero();

    var withToken = call("POST", RUNS, runBody("CI"), "Bearer " + CI);
    assertThat(withToken.statusCode()).as(withToken.body()).isEqualTo(202);
    assertThat((String) JsonPath.read(withToken.body(), "$.trigger")).isEqualTo("CI");
    assertThat(
            count("SELECT count(*) FROM api_tokens WHERE name = 'ci' AND last_used_at IS NOT NULL"))
        .isEqualTo(1);
  }

  @Test
  void theDashboardsAnonymousCallsStillWork() {
    assertThat(call("POST", RUNS, runBody("MANUAL"), null).statusCode()).isEqualTo(202);
    assertThat(call("GET", "/api/v1/runs", null, null).statusCode()).isEqualTo(200);
  }

  @Test
  void ci004ABadTokenIs401EverywhereAndNeverFallsBackToAnonymous() {
    for (String authorization :
        new String[] {
          "Bearer not-a-known-token-0123456789abcdef",
          "Bearer " + CI + "x",
          "Basic dXNlcjpwYXNz",
          "Bearer ",
          "bearer " + CI,
          "Bearer " + REVOKED,
          "Bearer " + EXPIRED
        }) {
      for (String[] request :
          new String[][] {
            {"GET", "/api/v1/runs", null},
            {"POST", RUNS, runBody("MANUAL")},
            {"GET", "/api/v1/projects/" + LocalProject.ID + "/quality-policy", null}
          }) {
        var response = call(request[0], request[1], request[2], authorization);
        assertThat(response.statusCode())
            .as(authorization + " " + request[0] + " " + request[1] + " -> " + response.body())
            .isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body()).doesNotContain(CI);
      }
    }
    assertThat(count("SELECT count(*) FROM runs")).isZero();
    assertThat(call("GET", "/api/v1/runs", null, "Bearer " + REVOKED).body()).contains("revoked");
    assertThat(call("GET", "/api/v1/runs", null, "Bearer " + EXPIRED).body()).contains("expired");
  }

  @Test
  void scopesAreEnforced() {
    var write = call("POST", RUNS, runBody("CI"), "Bearer " + READER);
    assertThat(write.statusCode()).as(write.body()).isEqualTo(403);
    assertThat(write.body()).contains("forbidden").contains("runs:write");
    assertThat(call("GET", "/api/v1/runs", null, "Bearer " + READER).statusCode()).isEqualTo(200);
    String runId = createRun("checkout-smoke", "PASS", "chromium");
    var quality = call("GET", "/api/v1/runs/" + runId + "/quality", null, "Bearer " + READER);
    assertThat(quality.statusCode()).as(quality.body()).isEqualTo(403);
    assertThat(quality.body()).contains("quality:read");
    assertThat(call("GET", "/api/v1/runs/" + runId + "/quality", null, "Bearer " + CI).statusCode())
        .isEqualTo(200);
    assertThat(call("POST", "/api/v1/runs/" + runId + "/cancel", null, "Bearer " + CI).statusCode())
        .isEqualTo(202);
  }

  @Test
  void theCiTokenCannotLoosenItsOwnGate() {
    String policy =
        "{\"reason\":\"ci\",\"policy\":"
            + DEFAULT_POLICY.replace("\"maxFailedCases\":0", "\"maxFailedCases\":9")
            + "}";
    String quarantine =
        "{\"caseKey\":\"cart-total\",\"reason\":\"ci\",\"expiresAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\"}";
    String policyPath = "/api/v1/projects/" + LocalProject.ID + "/quality-policy";
    String quarantinePath = "/api/v1/projects/" + LocalProject.ID + "/quarantines";
    var put = call("PUT", policyPath, policy, "Bearer " + CI);
    assertThat(put.statusCode()).as(put.body()).isEqualTo(403);
    var post = call("POST", quarantinePath, quarantine, "Bearer " + CI);
    assertThat(post.statusCode()).as(post.body()).isEqualTo(403);
    assertThat(count("SELECT count(*) FROM quality_policies")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM quarantines")).isZero();
    // The same calls without a token are the dashboard's, open until PRD-12.
    assertThat(call("POST", quarantinePath, quarantine, null).statusCode()).isEqualTo(201);
  }

  @Test
  void theWorkerApiIsUnaffected() {
    var response = post(internalPort(), "/internal/v1/workers/register", "{}", CI);
    assertThat(response.statusCode()).isEqualTo(401);
  }
}
