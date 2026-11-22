package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.List;
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

/** PRD-05 FR-6 / AC-5 (UI-003): POST /runs/{runId}/rerun on real PostgreSQL. */
@SpringBootTest(properties = "testgrid.profile=local")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class RerunApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
    jdbc.sql("UPDATE suites SET enabled = true WHERE suite_key = 'checkout-smoke'").update();
  }

  /**
   * A three-engine run written straight to the tables, finished with the given target statuses. Not
   * created through the API: the test JVM shares one database with other cached test contexts, some
   * of which run dispatch loops and simulated workers, and those would pick up a QUEUED target.
   * Runs here never have a QUEUED target, so nothing else touches them.
   */
  private UUID finishedRun(String chromium, String firefox, String webkit) {
    return insertRun(
        "FAILED", true, Map.of("chromium", chromium, "firefox", firefox, "webkit", webkit));
  }

  /** An unfinished run: every target RUNNING (no attempt rows, so no lease for a reaper). */
  private UUID runningRun() {
    return insertRun("RUNNING", false, Map.of("chromium", "RUNNING", "firefox", "RUNNING"));
  }

  private UUID insertRun(String runStatus, boolean finished, Map<String, String> targets) {
    UUID id =
        jdbc.sql(
                """
                INSERT INTO runs (project_id, suite_id, status, trigger, fixture_mode, fixture_seed,
                                  commit_sha, branch, idempotency_key, request_hash, started_at,
                                  finished_at)
                SELECT project_id, id, :status, 'MANUAL', 'PRODUCT_DEFECT', 42, 'abc1234', 'main',
                       :key, decode(repeat('00', 32), 'hex'), now(),
                       CASE WHEN :finished THEN now() END
                FROM suites WHERE suite_key = 'checkout-smoke' AND is_current
                RETURNING id
                """)
            .param("status", runStatus)
            .param("key", "parent-" + UUID.randomUUID())
            .param("finished", finished)
            .query(UUID.class)
            .single();
    for (var e : targets.entrySet()) {
      boolean terminal = !e.getValue().equals("RUNNING");
      jdbc.sql(
              """
              INSERT INTO run_targets (run_id, target_key, executor, engine, status, attempt_count,
                                       finished_at)
              VALUES (:r, :key, 'LOCAL', :engine, :status, 1, CASE WHEN :terminal THEN now() END)
              """)
          .param("r", id)
          .param("key", "local:" + e.getKey() + ":0")
          .param("engine", e.getKey())
          .param("status", e.getValue())
          .param("terminal", terminal)
          .update();
    }
    return id;
  }

  private ResultActions rerun(UUID runId, String scope, String key) throws Exception {
    return mvc.perform(
        post("/api/v1/runs/" + runId + "/rerun")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scope\":\"" + scope + "\",\"idempotencyKey\":\"" + key + "\"}"));
  }

  private long runCount() {
    return RunTestSupport.count(jdbc, "runs");
  }

  @Test
  void ui003RerunOfFailedTargetsIsALinkedRunWithOnlyTheFailedTargets() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "TIMED_OUT");

    String body =
        rerun(parent, "FAILED_TARGETS", "rerun-1")
            .andExpect(status().isAccepted())
            .andExpect(
                header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/runs/")))
            .andExpect(jsonPath("$.trigger").value("RERUN"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.parentRunId").value(parent.toString()))
            .andExpect(jsonPath("$.rerunIds").isEmpty())
            .andExpect(jsonPath("$.targets[*].engine", contains("chromium", "webkit")))
            .andExpect(jsonPath("$.targets[*].status", contains("QUEUED", "QUEUED")))
            .andExpect(
                jsonPath("$.targets[*].targetKey", contains("local:chromium:0", "local:webkit:0")))
            // Same test as before: suite revision, fixture and source are copied.
            .andExpect(jsonPath("$.suiteKey").value("checkout-smoke"))
            .andExpect(jsonPath("$.fixture.mode").value("PRODUCT_DEFECT"))
            .andExpect(jsonPath("$.fixture.seed").value(42))
            .andExpect(jsonPath("$.source.branch").value("main"))
            .andExpect(jsonPath("$.source.commitSha").value("abc1234"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String rerunId = JsonPath.read(body, "$.id");

    // The link is visible from the old run too.
    mvc.perform(get("/api/v1/runs/" + parent))
        .andExpect(jsonPath("$.parentRunId").isEmpty())
        .andExpect(jsonPath("$.rerunIds", contains(rerunId)));
    String suiteRevision =
        jdbc.sql("SELECT suite_id::text FROM runs WHERE id = :r")
            .param("r", parent)
            .query(String.class)
            .single();
    assertThat(
            jdbc.sql("SELECT suite_id::text FROM runs WHERE id = :r::uuid")
                .param("r", rerunId)
                .query(String.class)
                .single())
        .isEqualTo(suiteRevision);
    String payload =
        jdbc.sql(
                "SELECT payload::text FROM run_events WHERE run_id = :r::uuid AND type ="
                    + " 'RUN_CREATED'")
            .param("r", rerunId)
            .query(String.class)
            .single();
    assertThat((String) JsonPath.read(payload, "$.parentRunId")).isEqualTo(parent.toString());
    assertThat((String) JsonPath.read(payload, "$.rerunScope")).isEqualTo("FAILED_TARGETS");
    assertThat((List<String>) JsonPath.read(payload, "$.targetKeys"))
        .containsExactly("local:chromium:0", "local:webkit:0");
  }

  @Test
  void infraErrorCountsAsFailedButCancelledDoesNot() throws Exception {
    UUID parent = finishedRun("INFRA_ERROR", "CANCELLED", "PASSED");
    rerun(parent, "FAILED_TARGETS", "rerun-infra")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.targets[*].engine", contains("chromium")));
  }

  @Test
  void scopeAllRerunsEveryTarget() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "CANCELLED");
    rerun(parent, "ALL", "rerun-all")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.targets[*].engine", contains("chromium", "firefox", "webkit")));
  }

  @Test
  void theSameKeyAndScopeReplaysTheSameRun() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "PASSED");
    String first =
        rerun(parent, "FAILED_TARGETS", "rerun-replay")
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long before = runCount();

    rerun(parent, "FAILED_TARGETS", "rerun-replay")
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replay", "true"))
        .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
    assertThat(runCount()).isEqualTo(before);
  }

  @Test
  void aKeyReusedForADifferentRequestIsAConflict() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "PASSED");
    rerun(parent, "FAILED_TARGETS", "rerun-conflict").andExpect(status().isAccepted());

    rerun(parent, "ALL", "rerun-conflict")
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://testgrid.local/problems/idempotency-conflict"));
    // The key space is shared with create-run: the parent's own key is taken too.
    String parentKey =
        jdbc.sql("SELECT idempotency_key FROM runs WHERE id = :r")
            .param("r", parent)
            .query(String.class)
            .single();
    rerun(parent, "ALL", parentKey).andExpect(status().isConflict());
  }

  @Test
  void aRunThatHasNotFinishedCannotBeRerun() throws Exception {
    UUID running = runningRun();
    long before = runCount();
    rerun(running, "ALL", "rerun-early")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/invalid-rerun"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RUNNING")));
    assertThat(runCount()).isEqualTo(before);
  }

  @Test
  void failedTargetsWithNothingFailedIsRefused() throws Exception {
    UUID parent = finishedRun("PASSED", "CANCELLED", "PASSED");
    long before = runCount();
    rerun(parent, "FAILED_TARGETS", "rerun-none")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/invalid-rerun"))
        .andExpect(jsonPath("$.errors[0].field").value("scope"));
    assertThat(runCount()).isEqualTo(before);
  }

  @Test
  void aSuiteRevisionThatIsNoLongerRunnableIsRefused() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "PASSED");
    jdbc.sql("UPDATE suites SET enabled = false WHERE suite_key = 'checkout-smoke'").update();
    rerun(parent, "FAILED_TARGETS", "rerun-stale-suite")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/unknown-suite"));
  }

  @Test
  void anUnknownRunIsNotFound() throws Exception {
    rerun(UUID.randomUUID(), "ALL", "rerun-404").andExpect(status().isNotFound());
  }

  @Test
  void theBodyIsCheckedStrictly() throws Exception {
    UUID parent = finishedRun("FAILED", "PASSED", "PASSED");
    for (String bad :
        new String[] {
          "{\"scope\":\"FAILED_CASES\",\"idempotencyKey\":\"k1\"}",
          "{\"scope\":\"ALL\"}",
          "{\"scope\":\"ALL\",\"idempotencyKey\":\"has space\"}",
          "{\"scope\":\"ALL\",\"idempotencyKey\":\"k2\",\"targets\":[]}"
        }) {
      mvc.perform(
              post("/api/v1/runs/" + parent + "/rerun")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bad))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/validation-failed"));
    }
  }
}
