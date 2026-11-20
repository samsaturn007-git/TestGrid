package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.body;
import static dev.testgrid.api.run.RunTestSupport.engines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

/** PRD-02 FR-6 / FR-7 and AC-4 (API-005) against real PostgreSQL. */
@SpringBootTest(properties = "testgrid.profile=local")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class RunQueryApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  private UUID suiteId;

  @BeforeEach
  void setUp() {
    RunTestSupport.clearRuns(jdbc);
    suiteId = RunTestSupport.currentSuiteId(jdbc, "checkout-smoke");
  }

  @AfterEach
  void cleanUp() {
    RunTestSupport.clearRuns(jdbc);
    jdbc.sql("DELETE FROM suites WHERE project_id <> :p").param("p", LocalProject.ID).update();
    jdbc.sql("DELETE FROM projects WHERE id <> :p").param("p", LocalProject.ID).update();
  }

  @Test
  void api005PagingWhileFiftyRunsAreInsertedHasNoDuplicatesOrGaps() throws Exception {
    // 50 existing runs over 10 timestamps, 5 runs each, so id has to break the ties.
    for (int t = 0; t < 10; t++) {
      for (int k = 0; k < 5; k++) {
        RunTestSupport.insertRun(
            jdbc, LocalProject.ID, suiteId, "now() - interval '" + (t + 1) + " minutes'");
      }
    }
    List<String> expected =
        jdbc.sql(
                "SELECT id::text FROM runs WHERE project_id = :p"
                    + " ORDER BY queued_at DESC, id DESC")
            .param("p", LocalProject.ID)
            .query(String.class)
            .list();

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int insertedDuringPaging = 0;
    int pages = 0;
    do {
      String json =
          mvc.perform(
                  get("/api/v1/runs")
                      .param("limit", "7")
                      .param("cursor", cursor == null ? "" : cursor))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      seen.addAll(JsonPath.read(json, "$.items[*].id"));
      cursor = JsonPath.read(json, "$.nextCursor");
      pages++;

      // New runs land at the head of the list, behind the cursor, between page fetches.
      for (int i = 0; i < 7 && insertedDuringPaging < 50; i++, insertedDuringPaging++) {
        mvc.perform(
                post(RUNS_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("checkout-smoke", engines("chromium"), "")))
            .andExpect(status().isAccepted());
      }
    } while (cursor != null);

    assertThat(insertedDuringPaging).isEqualTo(50);
    assertThat(pages).isEqualTo(8);
    assertThat(new HashSet<>(seen)).as("no duplicates").hasSameSizeAs(seen);
    assertThat(seen).as("no gaps, same order").isEqualTo(expected);
  }

  @Test
  void firstPageIsNewestFirstWithDefaultLimit() throws Exception {
    for (int i = 0; i < 25; i++) {
      RunTestSupport.insertRun(
          jdbc, LocalProject.ID, suiteId, "now() - interval '" + i + " seconds'");
    }
    String json =
        mvc.perform(get("/api/v1/runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.nextCursor").isString())
            .andExpect(jsonPath("$.items[0].suiteKey").value("checkout-smoke"))
            .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
            .andExpect(jsonPath("$.items[0].links.self").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> queued = JsonPath.read(json, "$.items[*].queuedAt");
    assertThat(queued).isSortedAccordingTo((a, b) -> b.compareTo(a));
  }

  @Test
  void lastPageHasNoCursorAndAnEmptyListIsValid() throws Exception {
    mvc.perform(get("/api/v1/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
    RunTestSupport.insertRun(jdbc, LocalProject.ID, suiteId, "now()");
    mvc.perform(get("/api/v1/runs").param("limit", "1"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
  }

  @Test
  void limitAndCursorAreValidated() throws Exception {
    for (String limit : List.of("0", "101", "-1", "abc")) {
      mvc.perform(get("/api/v1/runs").param("limit", limit))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/validation-failed"))
          .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }
    mvc.perform(get("/api/v1/runs").param("limit", "100")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/runs").param("cursor", "garbage!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cursor"));
  }

  @Test
  void runsOfAnotherProjectAreNotListedOrReadable() throws Exception {
    UUID other = otherProjectWithSuite();
    UUID foreignRun = RunTestSupport.insertRun(jdbc, other, otherSuiteId(other), "now()");

    mvc.perform(get("/api/v1/runs")).andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(get("/api/v1/runs/" + foreignRun))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/not-found"));
  }

  @Test
  void detailShowsTargetsLinksAndTheComputedAggregate() throws Exception {
    String json =
        mvc.perform(
                post(RUNS_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("checkout-smoke", engines("chromium", "firefox"), "")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = JsonPath.read(json, "$.id");

    mvc.perform(get("/api/v1/runs/" + runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.targets.length()").value(2))
        .andExpect(jsonPath("$.links.self").value("/api/v1/runs/" + runId))
        .andExpect(jsonPath("$.links.suite").value("/api/v1/suites/" + suiteId));

    setTarget(runId, "local:chromium:0", "RUNNING", 1, false);
    mvc.perform(get("/api/v1/runs/" + runId)).andExpect(jsonPath("$.status").value("RUNNING"));

    setTarget(runId, "local:chromium:0", "PASSED", 1, true);
    setTarget(runId, "local:firefox:0", "FAILED", 1, true);
    mvc.perform(get("/api/v1/runs/" + runId))
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.targets[0].status").value("PASSED"))
        .andExpect(jsonPath("$.targets[1].status").value("FAILED"));
  }

  @Test
  void unknownOrMalformedRunIdIsRejected() throws Exception {
    mvc.perform(get("/api/v1/runs/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/not-found"));
    mvc.perform(get("/api/v1/runs/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/validation-failed"));
  }

  private void setTarget(String runId, String key, String status, int attempts, boolean finished) {
    jdbc.sql(
            "UPDATE run_targets SET status = :s, attempt_count = :a,"
                + " finished_at = CASE WHEN :f THEN now() END"
                + " WHERE run_id = :r AND target_key = :k")
        .param("s", status)
        .param("a", attempts)
        .param("f", finished)
        .param("r", UUID.fromString(runId))
        .param("k", key)
        .update();
  }

  private UUID otherProjectWithSuite() {
    UUID other =
        jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other') RETURNING id")
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO suites (project_id, suite_key, revision, display_name, is_current,
                                timeout_ms, playwright_retries, allowed_engines, fixture_modes)
            VALUES (:p, 'checkout-smoke', 'cccccccccccc', 'Other', true, 60000, 0,
                    ARRAY['chromium'], ARRAY[]::text[])
            """)
        .param("p", other)
        .update();
    return other;
  }

  private UUID otherSuiteId(UUID other) {
    return jdbc.sql("SELECT id FROM suites WHERE project_id = :p")
        .param("p", other)
        .query(UUID.class)
        .single();
  }
}
