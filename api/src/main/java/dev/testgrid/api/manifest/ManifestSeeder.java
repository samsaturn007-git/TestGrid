package dev.testgrid.api.manifest;

import dev.testgrid.api.project.LocalProject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upserts suites and cases from the manifest at startup (PRD-02 FR-2). It runs as an {@link
 * ApplicationRunner}, after Flyway has migrated the schema, so an invalid manifest makes startup
 * fail.
 */
public class ManifestSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ManifestSeeder.class);

  // Arbitrary constant naming this lock. Two API instances starting together would otherwise
  // race on is_current and one would fail on the partial unique index.
  private static final long SEED_LOCK_KEY = 0x7467_5345_4544L;

  private final ManifestLoader loader;
  private final ManifestProperties properties;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public ManifestSeeder(
      ManifestLoader loader,
      ManifestProperties properties,
      JdbcClient jdbc,
      TransactionTemplate tx) {
    this.loader = loader;
    this.properties = properties;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<LoadedSuite> suites = loader.load(properties.rootPath());
    seed(LocalProject.ID, suites);
  }

  public record SeededSuite(String suiteKey, String revision, UUID suiteId, boolean created) {}

  public List<SeededSuite> seed(UUID projectId, List<LoadedSuite> suites) {
    List<SeededSuite> result = tx.execute(status -> seedInTransaction(projectId, suites));
    for (SeededSuite s : result) {
      log.info(
          "manifest suite {} revision {} ({})",
          s.suiteKey(),
          s.revision(),
          s.created() ? "new revision" : "unchanged");
    }
    return result;
  }

  private List<SeededSuite> seedInTransaction(UUID projectId, List<LoadedSuite> suites) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", SEED_LOCK_KEY).query().singleRow();

    List<SeededSuite> seeded = new ArrayList<>();
    for (LoadedSuite loaded : suites) {
      seeded.add(upsertSuite(projectId, loaded));
    }

    // Suites missing from the manifest, and older revisions, stop being current. Clear first,
    // then set: the partial unique index is checked row by row.
    List<UUID> currentIds = seeded.stream().map(SeededSuite::suiteId).toList();
    String notCurrent = currentIds.isEmpty() ? "" : " AND id NOT IN (:ids)";
    var clear =
        jdbc.sql(
                "UPDATE suites SET is_current = false WHERE project_id = :projectId AND is_current"
                    + notCurrent)
            .param("projectId", projectId);
    if (!currentIds.isEmpty()) {
      clear = clear.param("ids", currentIds);
    }
    clear.update();
    if (!currentIds.isEmpty()) {
      jdbc.sql("UPDATE suites SET is_current = true WHERE id IN (:ids) AND NOT is_current")
          .param("ids", currentIds)
          .update();
    }
    return seeded;
  }

  private SeededSuite upsertSuite(UUID projectId, LoadedSuite loaded) {
    SuiteManifest.Suite suite = loaded.suite();
    // The revision covers every column below, so an existing (key, revision) row already holds
    // exactly this content and is left alone.
    var inserted =
        jdbc.sql(
                """
                INSERT INTO suites (project_id, suite_key, revision, display_name, enabled,
                                    timeout_ms, playwright_retries, allowed_engines, fixture_modes)
                VALUES (:projectId, :suiteKey, :revision, :displayName, :enabled,
                        :timeoutMs, :retries, :engines, :fixtureModes)
                ON CONFLICT (project_id, suite_key, revision) DO NOTHING
                RETURNING id
                """)
            .param("projectId", projectId)
            .param("suiteKey", suite.suiteKey())
            .param("revision", loaded.revision())
            .param("displayName", suite.displayName())
            .param("enabled", suite.isEnabled())
            .param("timeoutMs", suite.timeoutMs())
            .param("retries", suite.playwrightRetries())
            .param("engines", suite.allowedEngines().toArray(String[]::new))
            .param("fixtureModes", suite.fixtureModes().toArray(String[]::new))
            .query(UUID.class)
            .optional();

    if (inserted.isEmpty()) {
      UUID existing =
          jdbc.sql(
                  "SELECT id FROM suites WHERE project_id = :projectId AND suite_key = :suiteKey"
                      + " AND revision = :revision")
              .param("projectId", projectId)
              .param("suiteKey", suite.suiteKey())
              .param("revision", loaded.revision())
              .query(UUID.class)
              .single();
      return new SeededSuite(suite.suiteKey(), loaded.revision(), existing, false);
    }

    UUID suiteId = inserted.get();
    for (SuiteManifest.Case c : suite.cases()) {
      jdbc.sql(
              """
              INSERT INTO test_cases (suite_id, case_key, title, tag, critical, expected_duration_ms)
              VALUES (:suiteId, :caseKey, :title, :tag, :critical, :expectedMs)
              """)
          .param("suiteId", suiteId)
          .param("caseKey", c.caseKey())
          .param("title", c.title())
          .param("tag", c.tag())
          .param("critical", c.critical())
          .param("expectedMs", c.expectedDurationMs())
          .update();
    }
    return new SeededSuite(suite.suiteKey(), loaded.revision(), suiteId, true);
  }
}
