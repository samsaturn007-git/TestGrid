package dev.testgrid.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-06 T2: V8__artifacts.sql. The required indexes exist and are used, and the CHECKs hold even
 * when the API is bypassed. Each test runs in a transaction that is rolled back.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Transactional
class ArtifactSchemaTest {

  @Autowired private JdbcClient jdbc;

  @Test
  void requiredArtifactIndexesExist() {
    List<String> defs =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'artifacts'")
            .query(String.class)
            .list();
    assertThat(defs)
        .anyMatch(d -> d.contains("USING btree (attempt_id)"))
        .anyMatch(d ->
            d.contains("USING btree (expires_at)") && d.contains("(state = 'AVAILABLE'::text)"))
        .anyMatch(d -> d.contains("UNIQUE INDEX") && d.contains("(storage_key)"));
  }

  /**
   * Contracts §3: "the evidence must check them with EXPLAIN". Seq scans are off so an empty table
   * still shows which index the planner can use.
   */
  @Test
  void lookupsByAttemptAndThePurgeScanUseTheirIndexes() {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    String byAttempt =
        String.join(
            "\n",
            jdbc.sql(
                    "EXPLAIN SELECT id FROM artifacts WHERE attempt_id = '"
                        + UUID.randomUUID()
                        + "'")
                .query(String.class)
                .list());
    String expiring =
        String.join(
            "\n",
            jdbc.sql(
                    "EXPLAIN SELECT id FROM artifacts WHERE state = 'AVAILABLE'"
                        + " AND expires_at <= now() ORDER BY expires_at LIMIT 100")
                .query(String.class)
                .list());

    assertThat(byAttempt).contains("artifacts_attempt");
    assertThat(expiring).contains("artifacts_expiring");
  }

  /** In a savepoint, so one rejected insert doesn't abort the test's transaction. */
  private void insert(String kind, String mime, long size, String key, String sha) {
    jdbc.sql("SAVEPOINT bad_row").update();
    try {
      jdbc.sql(
              "INSERT INTO artifacts (attempt_id, kind, mime, storage_key, byte_size, sha256,"
                  + " expires_at) VALUES (:a, :k, :m, :key, :s, :sha, now() + interval '1 day')")
          .param("a", UUID.randomUUID())
          .param("k", kind)
          .param("m", mime)
          .param("key", key)
          .param("s", size)
          .param("sha", sha)
          .update();
    } finally {
      jdbc.sql("ROLLBACK TO SAVEPOINT bad_row").update();
    }
  }

  // CHECK constraints run before the attempt foreign key, so each insert fails on the rule named.
  @Test
  void kindMimeSizeKeyAndShaAreChecked() {
    String key = "2026/11/23/0f8fad5b-d9cb-469f-a165-70867728950e.png";
    String sha = "a".repeat(64);
    assertThatThrownBy(() -> insert("SCREENSHOT", "application/zip", 10, key, sha))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("artifacts_kind_mime");
    assertThatThrownBy(() -> insert("SCREENSHOT", "image/png", 5 * 1024 * 1024 + 1, key, sha))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("artifacts_kind_size");
    assertThatThrownBy(() -> insert("SCREENSHOT", "image/png", 10, "../../etc/passwd", sha))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("storage_key");
    assertThatThrownBy(() -> insert("SCREENSHOT", "image/png", 10, key, "XYZ"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("sha256");
    assertThatThrownBy(() -> insert("VIDEO", "image/png", 10, key, sha))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("kind");
    assertThatThrownBy(() -> insert("SCREENSHOT", "image/png", 0, key, sha))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("byte_size");
  }
}
