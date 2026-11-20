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
}
