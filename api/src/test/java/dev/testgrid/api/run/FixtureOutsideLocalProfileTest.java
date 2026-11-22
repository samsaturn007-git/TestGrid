package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.body;
import static dev.testgrid.api.run.RunTestSupport.count;
import static dev.testgrid.api.run.RunTestSupport.engines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.support.PostgresTestcontainer;
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

/** API-003: with TESTGRID_PROFILE unset (the default), fixture modes are refused. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class FixtureOutsideLocalProfileTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
  }

  @Test
  void api003FixtureModeOutsideTheLocalProfileIsRejected() throws Exception {
    mvc.perform(
            post(RUNS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        "checkout-smoke",
                        engines("chromium"),
                        "\"fixture\":{\"mode\":\"PASS\",\"seed\":1}")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/fixture-not-allowed"))
        .andExpect(jsonPath("$.errors[0].field").value("fixture"));

    assertThat(count(jdbc, "runs")).isZero();
    assertThat(count(jdbc, "run_events")).isZero();
  }

  @Test
  void aRunWithoutFixtureIsStillAccepted() throws Exception {
    mvc.perform(
            post(RUNS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("checkout-smoke", engines("chromium"), "")))
        .andExpect(status().isAccepted());
  }

  @Test
  void aRerunOfAFixtureRunIsRefusedOutsideTheLocalProfile() throws Exception {
    // Created directly, as if the profile had been local when it ran.
    java.util.UUID parent =
        jdbc.sql(
                """
                INSERT INTO runs (project_id, suite_id, status, trigger, fixture_mode, fixture_seed,
                                  idempotency_key, request_hash, started_at, finished_at)
                SELECT project_id, id, 'FAILED', 'MANUAL', 'PASS', 1, 'fixture-parent',
                       decode(repeat('00', 32), 'hex'), now(), now()
                FROM suites WHERE suite_key = 'checkout-smoke' AND is_current
                RETURNING id
                """)
            .query(java.util.UUID.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO run_targets (run_id, target_key, executor, engine, status, finished_at)
            VALUES (:r, 'local:chromium:0', 'LOCAL', 'chromium', 'FAILED', now())
            """)
        .param("r", parent)
        .update();

    mvc.perform(
            post("/api/v1/runs/" + parent + "/rerun")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"FAILED_TARGETS\",\"idempotencyKey\":\"k-fixture\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/fixture-not-allowed"));
    assertThat(count(jdbc, "runs")).isEqualTo(1);
  }
}
