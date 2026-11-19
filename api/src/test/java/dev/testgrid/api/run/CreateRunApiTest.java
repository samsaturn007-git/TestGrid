package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.body;
import static dev.testgrid.api.run.RunTestSupport.count;
import static dev.testgrid.api.run.RunTestSupport.engines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** PRD-02 FR-4 / AC-3 (API-003) with the local profile, against real PostgreSQL. */
@SpringBootTest(properties = "testgrid.profile=local")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class CreateRunApiTest {

  private static final String PROBLEMS = "https://testgrid.local/problems/";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    RunTestSupport.clearRuns(jdbc);
    RunTestSupport.ensureChromiumOnlySuite(jdbc);
  }

  @AfterEach
  void cleanUp() {
    RunTestSupport.clearRuns(jdbc);
  }

  @Test
  void createsRunTargetsAndEventAndReturns202WithLocation() throws Exception {
    String request =
        body(
            "checkout-smoke",
            engines("firefox", "chromium"),
            "\"source\":{\"commitSha\":\"0123456789abcdef\",\"branch\":\"main\","
                + "\"ciRef\":\"gh:123456/1\"}");

    String location =
        post(request)
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", startsWith("/api/v1/runs/")))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.suiteKey").value("checkout-smoke"))
            .andExpect(jsonPath("$.suiteRevision").value(matchesPattern("[0-9a-f]{12}")))
            .andExpect(jsonPath("$.trigger").value("MANUAL"))
            .andExpect(jsonPath("$.sharding.mode").value("NONE"))
            .andExpect(jsonPath("$.sharding.shards").value(1))
            .andExpect(jsonPath("$.source.branch").value("main"))
            .andExpect(jsonPath("$.targets.length()").value(2))
            .andExpect(jsonPath("$.targets[0].targetKey").value("local:chromium:0"))
            .andExpect(jsonPath("$.targets[0].engine").value("chromium"))
            .andExpect(jsonPath("$.targets[0].status").value("QUEUED"))
            .andExpect(jsonPath("$.targets[1].targetKey").value("local:firefox:0"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    UUID runId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    assertThat(count(jdbc, "runs")).isEqualTo(1);
    assertThat(count(jdbc, "run_targets")).isEqualTo(2);
    Map<String, Object> event =
        jdbc.sql("SELECT type, payload::text AS payload FROM run_events WHERE run_id = :r")
            .param("r", runId)
            .query()
            .singleRow();
    assertThat(event.get("type")).isEqualTo("RUN_CREATED");
    assertThat((String) event.get("payload"))
        .contains("\"suiteKey\": \"checkout-smoke\"")
        .contains("\"targetKeys\": [\"local:firefox:0\", \"local:chromium:0\"]");
  }

  @Test
  void fixtureModeIsAcceptedInTheLocalProfile() throws Exception {
    post(body(
            "checkout-smoke",
            engines("chromium"),
            "\"fixture\":{\"mode\":\"PRODUCT_DEFECT\",\"seed\":42}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.fixture.mode").value("PRODUCT_DEFECT"))
        .andExpect(jsonPath("$.fixture.seed").value(42));
  }

  @Test
  void api003UnknownSuiteIsRejected() throws Exception {
    expectProblem(post(body("no-such-suite", engines("chromium"), "")), 422, "unknown-suite")
        .andExpect(jsonPath("$.errors[0].field").value("suiteKey"));
    assertNothingPersisted();
  }

  @Test
  void api003EngineOutsideAllowedEnginesIsRejected() throws Exception {
    expectProblem(
            post(body("chromium-only", engines("chromium", "webkit"), "")),
            422,
            "engine-not-allowed")
        .andExpect(jsonPath("$.errors[0].field").value("targets[1].engine"));
    assertNothingPersisted();
  }

  @Test
  void api003FixtureModeTheSuiteDoesNotDeclareIsRejected() throws Exception {
    expectProblem(
        post(
            body(
                "chromium-only",
                engines("chromium"),
                "\"fixture\":{\"mode\":\"PRODUCT_DEFECT\",\"seed\":1}")),
        422,
        "fixture-not-allowed");
    assertNothingPersisted();
  }

  @Test
  void api003UnknownJsonFieldIsRejected() throws Exception {
    expectProblem(
            post(body("checkout-smoke", engines("chromium"), "\"command\":\"rm -rf /\"")),
            400,
            "validation-failed")
        .andExpect(jsonPath("$.errors[0].field").value("command"))
        .andExpect(jsonPath("$.errors[0].message").value("unknown field"));
    assertNothingPersisted();
  }

  @Test
  void api003UnknownNestedFieldIsRejectedWithItsPath() throws Exception {
    String targets = "[{\"executor\":\"LOCAL\",\"engine\":\"chromium\",\"url\":\"http://x\"}]";
    expectProblem(post(body("checkout-smoke", targets, "")), 400, "validation-failed")
        .andExpect(jsonPath("$.errors[0].field").value("targets[0].url"));
    assertNothingPersisted();
  }

  @Test
  void api003OversizedStringIsRejected() throws Exception {
    String longBranch = "b".repeat(201);
    expectProblem(
            post(
                body(
                    "checkout-smoke",
                    engines("chromium"),
                    "\"source\":{\"branch\":\"" + longBranch + "\"}")),
            400,
            "validation-failed")
        .andExpect(jsonPath("$.errors[*].field", hasItem("source.branch")));
    assertNothingPersisted();
  }

  @Test
  void api003UrlLikeStringsAreRejected() throws Exception {
    expectProblem(
            post(
                body(
                    "checkout-smoke",
                    engines("chromium"),
                    "\"source\":{\"branch\":\"http://evil.example\",\"ciRef\":\"https://evil.example/x\"}")),
            400,
            "validation-failed")
        .andExpect(jsonPath("$.errors[*].field", hasItem("source.branch")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("source.ciRef")));
    assertNothingPersisted();
  }

  @Test
  void unknownEngineValueIsAValidationError() throws Exception {
    expectProblem(post(body("checkout-smoke", engines("edge"), "")), 400, "validation-failed")
        .andExpect(jsonPath("$.errors[0].field").value("targets[0].engine"));
    assertNothingPersisted();
  }

  @Test
  void missingRequiredFieldsAreAllReported() throws Exception {
    expectProblem(post("{}"), 400, "validation-failed")
        .andExpect(jsonPath("$.errors[*].field", hasItem("suiteKey")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("targets")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("trigger")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("idempotencyKey")));
    assertNothingPersisted();
  }

  @Test
  void malformedJsonIsRejected() throws Exception {
    expectProblem(post("{\"suiteKey\": "), 400, "validation-failed")
        .andExpect(jsonPath("$.detail").value("malformed JSON"));
  }

  @Test
  void duplicateJsonKeysAreRejected() throws Exception {
    expectProblem(
        post("{\"suiteKey\":\"checkout-smoke\",\"suiteKey\":\"chromium-only\"}"),
        400,
        "validation-failed");
  }

  @Test
  void stringsAreNotCoercedToNumbersOrEnumsFromNumbers() throws Exception {
    expectProblem(
            post(
                body(
                    "checkout-smoke",
                    engines("chromium"),
                    "\"fixture\":{\"mode\":\"PASS\",\"seed\":\"42\"}")),
            400,
            "validation-failed")
        .andExpect(jsonPath("$.errors[0].field").value("fixture.seed"));
    expectProblem(
        post(body("checkout-smoke", "[{\"executor\":0,\"engine\":\"chromium\"}]", "")),
        400,
        "validation-failed");
    assertNothingPersisted();
  }

  @Test
  void bodyOver64KbIsRejectedBeforeParsing() throws Exception {
    String padding = "x".repeat(70 * 1024);
    expectProblem(post("{\"suiteKey\":\"" + padding + "\"}"), 400, "validation-failed")
        .andExpect(jsonPath("$.detail").value("request body exceeds 64 KB"));
    assertNothingPersisted();
  }

  @Test
  void duplicateTargetsBrowserStackRerunAndShardingAreRejected() throws Exception {
    String targets =
        "[{\"executor\":\"LOCAL\",\"engine\":\"chromium\"},"
            + "{\"executor\":\"LOCAL\",\"engine\":\"chromium\"},"
            + "{\"executor\":\"BROWSERSTACK\",\"engine\":\"firefox\"}]";
    String request =
        body(
                "checkout-smoke",
                targets,
                "\"sharding\":{\"mode\":\"DURATION_BALANCED\",\"shards\":2}")
            .replace("\"MANUAL\"", "\"RERUN\"");
    expectProblem(post(request), 400, "validation-failed")
        .andExpect(jsonPath("$.errors[*].field", hasItem("trigger")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("targets[1]")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("targets[2].executor")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("sharding")));
    assertNothingPersisted();
  }

  @Test
  void unknownProjectIsNotFound() throws Exception {
    expectProblem(
        mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/projects/" + UUID.randomUUID() + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("checkout-smoke", engines("chromium"), ""))),
        404,
        "not-found");
    assertNothingPersisted();
  }

  @Test
  void nonUuidProjectIdIsAValidationError() throws Exception {
    expectProblem(
        mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/projects/demo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("checkout-smoke", engines("chromium"), ""))),
        400,
        "validation-failed");
  }

  private ResultActions post(String json) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post(RUNS_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static ResultActions expectProblem(ResultActions result, int status, String slug)
      throws Exception {
    return result
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + slug))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(content().string(not(containsString("Exception"))))
        .andExpect(content().string(not(containsString("\tat "))));
  }

  private void assertNothingPersisted() {
    assertThat(count(jdbc, "runs")).isZero();
    assertThat(count(jdbc, "run_targets")).isZero();
    assertThat(count(jdbc, "run_events")).isZero();
  }
}
