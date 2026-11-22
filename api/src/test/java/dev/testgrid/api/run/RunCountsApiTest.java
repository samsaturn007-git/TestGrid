package dev.testgrid.api.run;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** PRD-05 FR-2 / T6: GET /projects/{projectId}/run-counts on real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class RunCountsApiTest {

  private static final String URL = "/api/v1/projects/" + LocalProject.ID + "/run-counts";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
  }

  private void run(String status, String queuedAgo) {
    boolean terminal = !status.equals("QUEUED") && !status.equals("RUNNING");
    jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key, request_hash,
                              queued_at, started_at, finished_at)
            SELECT project_id, id, :s, 'MANUAL', :k, decode(repeat('00', 32), 'hex'),
                   now() - CAST(:ago AS interval),
                   CASE WHEN :s <> 'QUEUED' THEN now() END,
                   CASE WHEN :terminal THEN now() END
            FROM suites WHERE project_id = :p AND suite_key = 'checkout-smoke' AND is_current
            """)
        .param("s", status)
        .param("k", "count-" + UUID.randomUUID())
        .param("ago", queuedAgo)
        .param("terminal", terminal)
        .param("p", LocalProject.ID)
        .update();
  }

  @Test
  void countsRunsQueuedInTheLast24HoursByCurrentStatusWithEveryStatusListed() throws Exception {
    run("FAILED", "1 hour");
    run("FAILED", "23 hours");
    run("PASSED", "5 minutes");
    run("RUNNING", "1 minute");
    run("PASSED", "25 hours"); // outside the window
    run("FAILED", "3 days"); // outside the window

    mvc.perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hours").value(24))
        .andExpect(jsonPath("$.total").value(4))
        .andExpect(
            jsonPath("$.items[*].status")
                .value(
                    contains(
                        "QUEUED",
                        "RUNNING",
                        "PASSED",
                        "FAILED",
                        "TIMED_OUT",
                        "INFRA_ERROR",
                        "CANCELLED")))
        .andExpect(jsonPath("$.items[*].count").value(contains(0, 1, 1, 2, 0, 0, 0)))
        .andExpect(jsonPath("$.from").isNotEmpty())
        .andExpect(jsonPath("$.to").isNotEmpty());
  }

  @Test
  void theWindowCanBeChosen() throws Exception {
    run("PASSED", "30 minutes");
    run("PASSED", "3 hours");
    mvc.perform(get(URL).param("hours", "1"))
        .andExpect(jsonPath("$.hours").value(1))
        .andExpect(jsonPath("$.total").value(1));
    mvc.perform(get(URL).param("hours", "168")).andExpect(jsonPath("$.total").value(2));
  }

  @Test
  void anEmptyWindowIsAllZeros() throws Exception {
    mvc.perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items.length()").value(7))
        .andExpect(jsonPath("$.items[*].count").value(contains(0, 0, 0, 0, 0, 0, 0)));
  }

  @Test
  void hoursOutsideTheRangeIsRejected() throws Exception {
    for (String bad : new String[] {"0", "169", "-1", "abc"}) {
      mvc.perform(get(URL).param("hours", bad))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/validation-failed"));
    }
  }

  @Test
  void aProjectOutsideTheCallersScopeIsNotFound() throws Exception {
    mvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/run-counts"))
        .andExpect(status().isNotFound());
  }
}
