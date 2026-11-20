package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.body;
import static dev.testgrid.api.run.RunTestSupport.count;
import static dev.testgrid.api.run.RunTestSupport.engines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.support.PostgresTestcontainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD-02 FR-4: the run, its targets and RUN_CREATED commit in one transaction. The event insert is
 * the last write, so failing it proves the earlier run and target rows are rolled back too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class RunCreationAtomicityTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private RunRepository runs;

  @BeforeEach
  @AfterEach
  void clear() {
    reset(runs);
    RunTestSupport.clearRuns(jdbc);
  }

  @Test
  void aFailureWritingTheEventLeavesNoRunOrTargets() throws Exception {
    doThrow(new DataAccessResourceFailureException("simulated connection loss"))
        .when(runs)
        .insertRunEvent(any(), any(EventType.class), any());

    mvc.perform(
            post(RUNS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("checkout-smoke", engines("chromium", "firefox"), "")))
        .andExpect(status().isInternalServerError());

    assertThat(count(jdbc, "runs")).isZero();
    assertThat(count(jdbc, "run_targets")).isZero();
    assertThat(count(jdbc, "run_events")).isZero();
  }

  @Test
  void theSameRequestSucceedsOnceTheFailureIsGone() throws Exception {
    String request = body("checkout-smoke", engines("chromium"), "");
    doThrow(new DataAccessResourceFailureException("simulated connection loss"))
        .when(runs)
        .insertRunEvent(any(), any(EventType.class), any());
    mvc.perform(post(RUNS_URL).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isInternalServerError());

    reset(runs);
    // The failed attempt didn't keep the idempotency key, so the retry creates the run.
    mvc.perform(post(RUNS_URL).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isAccepted());
    assertThat(count(jdbc, "runs")).isEqualTo(1);
    assertThat(count(jdbc, "run_events")).isEqualTo(1);
  }
}
