package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP on both ports against Testcontainers PostgreSQL. Every subclass uses the same
 * properties, so they share one application context. The token is a fake test value.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "testgrid.internal.port=0",
      "testgrid.internal.worker-token=" + WorkerApiTestBase.TOKEN,
      "testgrid.profile=local",
      "testgrid.artifacts.root=" + WorkerApiTestBase.ARTIFACT_ROOT
    })
@Import(PostgresTestcontainer.class)
abstract class WorkerApiTestBase {

  static final String TOKEN = "test-only-worker-token-0123456789abcdef";
  static final String ARTIFACT_ROOT = "target/test-artifacts/worker-api";
  static final String CHECKOUT_CASES_JSON =
      "[\"add-to-cart\",\"apply-coupon\",\"browse-catalog\",\"card-declined\",\"cart-total\","
          + "\"checkout-happy-path\"]";
  static final List<String> CHECKOUT_CASES =
      List.of(
          "add-to-cart",
          "apply-coupon",
          "browse-catalog",
          "card-declined",
          "cart-total",
          "checkout-happy-path");

  @LocalServerPort int publicPort;
  @Autowired InternalServerConfig.InternalConnector internal;
  @Autowired JdbcClient jdbc;

  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void clean() {
    jdbc.sql(
            "TRUNCATE workers, outbox_events, artifacts, attempt_heartbeats, case_results, attempts, failure_fingerprints, audit_events, run_events, run_targets, runs")
        .update();
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 3").update();
  }

  int internalPort() {
    return internal.localPort();
  }

  HttpResponse<String> post(int port, String path, String body, String token) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  HttpResponse<String> get(int port, String path) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  HttpResponse<String> internalPost(String path, String body) {
    return post(internalPort(), path, body, TOKEN);
  }

  /** Creates a run through the public API and returns its ID. */
  String createRun(String suiteKey, String fixtureMode, String... engines) {
    StringBuilder targets = new StringBuilder("[");
    for (int i = 0; i < engines.length; i++) {
      targets
          .append(i == 0 ? "" : ",")
          .append("{\"executor\":\"LOCAL\",\"engine\":\"")
          .append(engines[i])
          .append("\"}");
    }
    targets.append(']');
    String body =
        "{\"suiteKey\":\""
            + suiteKey
            + "\",\"targets\":"
            + targets
            + ",\"trigger\":\"MANUAL\",\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\""
            + (fixtureMode == null
                ? ""
                : ",\"fixture\":{\"mode\":\"" + fixtureMode + "\",\"seed\":42}")
            + "}";
    var response = post(publicPort, "/api/v1/projects/" + LocalProject.ID + "/runs", body, null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    return JsonPath.read(response.body(), "$.id");
  }

  record Claim(String attemptId, String token, String targetId, String runId, String body) {}

  Claim claim(String... engines) {
    var response = claimResponse("w-test", engines);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return new Claim(
        JsonPath.read(response.body(), "$.attemptId"),
        JsonPath.read(response.body(), "$.leaseToken"),
        JsonPath.read(response.body(), "$.targetId"),
        JsonPath.read(response.body(), "$.runId"),
        response.body());
  }

  /**
   * What the dispatcher and the broker do for a real worker, done by hand: take the oldest
   * undispatched QUEUED target for these engines, mark it dispatched (new generation) and claim it
   * by ID. The background dispatch loops are off in tests.
   */
  HttpResponse<String> claimResponse(String workerId, String... engines) {
    var dispatched = dispatchOldest(engines);
    return claimTarget(workerId, dispatched.targetId(), dispatched.generation());
  }

  record Dispatched(UUID targetId, int generation) {}

  Dispatched dispatchOldest(String... engines) {
    return jdbc.sql(
            """
            UPDATE run_targets
            SET dispatched_at = now(), dispatch_generation = dispatch_generation + 1
            WHERE id = (SELECT id FROM run_targets
                        WHERE status = 'QUEUED' AND dispatched_at IS NULL
                          AND engine IN (:engines)
                        ORDER BY queued_at, id LIMIT 1)
            RETURNING id, dispatch_generation
            """)
        .param("engines", List.of(engines))
        .query((rs, i) ->
            new Dispatched(rs.getObject("id", UUID.class), rs.getInt("dispatch_generation")))
        .single();
  }

  HttpResponse<String> claimTarget(String workerId, UUID targetId, int generation) {
    return internalPost(
        "/internal/v1/targets/" + targetId + "/claim",
        "{\"workerId\":\""
            + workerId
            + "\",\"messageId\":\""
            + UUID.randomUUID()
            + "\",\"dispatchGeneration\":"
            + generation
            + "}");
  }

  HttpResponse<String> start(Claim claim) {
    return lease(claim, "start");
  }

  HttpResponse<String> lease(Claim claim, String operation) {
    return internalPost(
        "/internal/v1/attempts/" + claim.attemptId() + "/" + operation,
        "{\"leaseToken\":\"" + claim.token() + "\"}");
  }

  HttpResponse<String> complete(Claim claim, String key, String status, String casesJson) {
    return complete(claim.attemptId(), claim.token(), key, status, casesJson, "null");
  }

  HttpResponse<String> complete(
      String attemptId, String token, String key, String status, String casesJson, String error) {
    return internalPost(
        "/internal/v1/attempts/" + attemptId + "/complete",
        "{\"leaseToken\":\""
            + token
            + "\",\"completionKey\":\""
            + key
            + "\",\"status\":\""
            + status
            + "\",\"caseResults\":"
            + casesJson
            + ",\"workerError\":"
            + error
            + "}");
  }

  /** One result per checkout-smoke case; {@code overrides} replaces whole entries by case key. */
  String checkoutResults(Map<String, String> overrides) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < CHECKOUT_CASES.size(); i++) {
      String key = CHECKOUT_CASES.get(i);
      out.append(i == 0 ? "" : ",")
          .append(
              overrides.getOrDefault(
                  key,
                  "{\"caseKey\":\""
                      + key
                      + "\",\"status\":\"PASSED\",\"durationMs\":1200,\"retryCount\":0,"
                      + "\"errorClass\":null,\"errorMessage\":null}"));
    }
    return out.append(']').toString();
  }

  long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  String one(String sql, Object id) {
    return jdbc.sql(sql).param("id", UUID.fromString(id.toString())).query(String.class).single();
  }

  Map<String, Object> row(String sql, Object id) {
    return jdbc.sql(sql).param("id", UUID.fromString(id.toString())).query().singleRow();
  }
}
