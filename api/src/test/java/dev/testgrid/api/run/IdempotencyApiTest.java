package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
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

/** PRD-02 FR-5 / AC-2: API-001 (replay) and API-002 (conflict), sequentially. */
@SpringBootTest(properties = "testgrid.profile=local")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class IdempotencyApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
    jdbc.sql("DELETE FROM suites WHERE project_id <> :p").param("p", LocalProject.ID).update();
    jdbc.sql("DELETE FROM projects WHERE id <> :p").param("p", LocalProject.ID).update();
  }

  private static String request(String key, String engines, String extra) {
    return "{\"suiteKey\":\"checkout-smoke\",\"targets\":"
        + engines
        + ",\"trigger\":\"CI\",\"idempotencyKey\":\""
        + key
        + "\""
        + extra
        + "}";
  }

  private static final String CHROMIUM_FIREFOX =
      "[{\"executor\":\"LOCAL\",\"engine\":\"chromium\"},{\"executor\":\"LOCAL\",\"engine\":\"firefox\"}]";

  @Test
  void api001SameKeyAndPayloadReplaysTheOriginalRun() throws Exception {
    String body = request("ci-run-1", CHROMIUM_FIREFOX, "");

    String first =
        runId(
            postTo(RUNS_URL, body)
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(RunController.REPLAY_HEADER)));
    String second =
        runId(
            postTo(RUNS_URL, body)
                .andExpect(status().isOk())
                .andExpect(header().string(RunController.REPLAY_HEADER, "true"))
                .andExpect(header().string("Location", "/api/v1/runs/" + first)));

    assertThat(second).isEqualTo(first);
    assertThat(count(jdbc, "runs")).isEqualTo(1);
    assertThat(count(jdbc, "run_targets")).isEqualTo(2);
    assertThat(count(jdbc, "run_events")).isEqualTo(1);
  }

  @Test
  void api001ReorderedTargetsAndSpelledOutDefaultsAreTheSameRequest() throws Exception {
    String first =
        runId(
            postTo(RUNS_URL, request("ci-run-2", CHROMIUM_FIREFOX, ""))
                .andExpect(status().isAccepted()));

    String reordered =
        "[{\"engine\":\"firefox\",\"executor\":\"LOCAL\"},{\"executor\":\"LOCAL\",\"engine\":\"chromium\"}]";
    String replay =
        runId(
            postTo(
                    RUNS_URL,
                    request(
                        "ci-run-2",
                        reordered,
                        ",\"sharding\":{\"mode\":\"NONE\",\"shards\":1},\"fixture\":null"))
                .andExpect(status().isOk()));

    assertThat(replay).isEqualTo(first);
    assertThat(count(jdbc, "runs")).isEqualTo(1);
  }

  @Test
  void api002SameKeyWithADifferentPayloadIsAConflict() throws Exception {
    String first =
        runId(
            postTo(RUNS_URL, request("ci-run-3", CHROMIUM_FIREFOX, ""))
                .andExpect(status().isAccepted()));

    String onlyChromium = "[{\"executor\":\"LOCAL\",\"engine\":\"chromium\"}]";
    postTo(RUNS_URL, request("ci-run-3", onlyChromium, ""))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/idempotency-conflict"))
        .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"))
        .andExpect(jsonPath("$.detail").value(containsString(first)));

    assertThat(count(jdbc, "runs")).isEqualTo(1);
    assertThat(count(jdbc, "run_targets")).isEqualTo(2);
    assertThat(count(jdbc, "run_events")).isEqualTo(1);
  }

  @Test
  void api002AFixtureChangeIsAlsoAConflict() throws Exception {
    postTo(
            RUNS_URL,
            request("ci-run-4", CHROMIUM_FIREFOX, ",\"fixture\":{\"mode\":\"PASS\",\"seed\":1}"))
        .andExpect(status().isAccepted());
    postTo(
            RUNS_URL,
            request("ci-run-4", CHROMIUM_FIREFOX, ",\"fixture\":{\"mode\":\"PASS\",\"seed\":2}"))
        .andExpect(status().isConflict());
    assertThat(count(jdbc, "runs")).isEqualTo(1);
  }

  @Test
  void replayStillWorksAfterTheSuiteStopsBeingCurrent() throws Exception {
    String body = request("ci-run-5", CHROMIUM_FIREFOX, "");
    String first = runId(postTo(RUNS_URL, body).andExpect(status().isAccepted()));
    UUID suiteId =
        jdbc.sql("SELECT suite_id FROM runs WHERE id = :id")
            .param("id", UUID.fromString(first))
            .query(UUID.class)
            .single();
    jdbc.sql("UPDATE suites SET is_current = false WHERE id = :id").param("id", suiteId).update();
    try {
      assertThat(runId(postTo(RUNS_URL, body).andExpect(status().isOk()))).isEqualTo(first);
      // A new key is checked against the manifest and now fails.
      postTo(RUNS_URL, request("ci-run-6", CHROMIUM_FIREFOX, "")).andExpect(status().is(422));
    } finally {
      jdbc.sql("UPDATE suites SET is_current = true WHERE id = :id").param("id", suiteId).update();
    }
  }

  @Test
  void keysAreScopedToTheProject() throws Exception {
    UUID other =
        jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other') RETURNING id")
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO suites (project_id, suite_key, revision, display_name, is_current,
                                timeout_ms, playwright_retries, allowed_engines, fixture_modes)
            VALUES (:p, 'checkout-smoke', 'bbbbbbbbbbbb', 'Other smoke', true, 60000, 0,
                    ARRAY['chromium', 'firefox'], ARRAY[]::text[])
            """)
        .param("p", other)
        .update();
    String body = request("shared-key", CHROMIUM_FIREFOX, "");

    String inDemo = runId(postTo(RUNS_URL, body).andExpect(status().isAccepted()));
    String inOther =
        runId(postTo("/api/v1/projects/" + other + "/runs", body).andExpect(status().isAccepted()));

    assertThat(inOther).isNotEqualTo(inDemo);
    assertThat(count(jdbc, "runs")).isEqualTo(2);
  }

  private ResultActions postTo(String url, String json) throws Exception {
    return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static String runId(ResultActions result) throws Exception {
    return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
  }
}
