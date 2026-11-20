package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-4 (API-005): with enough rows that the planner has a real choice, the GET /runs queries use
 * the keyset index as an index range scan with no sort. The ANALYZE output is written to
 * target/evidence/ for docs/evidence/PRD-02.md.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class RunListExplainTest {

  private static final int ROWS = 5000;
  private static final Path EVIDENCE = Path.of("target/evidence/explain-runs-list.txt");

  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void fill() {
    RunTestSupport.clearRuns(jdbc);
    UUID suiteId = RunTestSupport.currentSuiteId(jdbc, "checkout-smoke");
    jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key, request_hash,
                              queued_at)
            SELECT :p, :s, 'QUEUED', 'CI', 'explain-' || g, sha256(g::text::bytea),
                   now() - (g || ' seconds')::interval
            FROM generate_series(1, :rows) AS g
            """)
        .param("p", LocalProject.ID)
        .param("s", suiteId)
        .param("rows", ROWS)
        .update();
    jdbc.sql("ANALYZE runs").update();
  }

  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
  }

  @Test
  void firstPageAndCursorPageUseTheKeysetIndexWithoutSorting() throws IOException {
    Map<String, Object> middle =
        jdbc.sql(
                "SELECT queued_at, id FROM runs ORDER BY queued_at DESC, id DESC OFFSET 2500"
                    + " LIMIT 1")
            .query()
            .singleRow();
    OffsetDateTime queuedAt =
        ((Timestamp) middle.get("queued_at")).toInstant().atOffset(ZoneOffset.UTC);

    String firstPlan = explain(RunRepository.LIST_FIRST_PAGE, null, null);
    String cursorPlan = explain(RunRepository.LIST_AFTER_CURSOR, queuedAt, (UUID) middle.get("id"));

    for (String plan : List.of(firstPlan, cursorPlan)) {
      assertThat(plan).contains("Index Scan using runs_project_queued_at_id on runs");
      assertThat(plan).doesNotContain("Sort Key");
      assertThat(plan).doesNotContain("Seq Scan on runs");
    }
    assertThat(cursorPlan).containsPattern("Index Cond: .*ROW\\(queued_at, id\\) < ROW\\(");

    Files.createDirectories(EVIDENCE.getParent());
    Files.writeString(
        EVIDENCE,
        "-- GET /runs first page (limit 20 + 1), "
            + ROWS
            + " runs\n"
            + firstPlan
            + "\n\n-- GET /runs page after a cursor in the middle\n"
            + cursorPlan
            + "\n");
  }

  private String explain(String sql, OffsetDateTime queuedAt, UUID id) {
    var query =
        jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
            .param("p", LocalProject.ID)
            .param("limit", 21);
    if (queuedAt != null) {
      query = query.param("queuedAt", queuedAt).param("id", id);
    }
    return String.join("\n", query.query(String.class).list());
  }
}
