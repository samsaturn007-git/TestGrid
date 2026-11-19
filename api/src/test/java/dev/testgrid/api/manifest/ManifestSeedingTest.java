package dev.testgrid.api.manifest;

import static dev.testgrid.api.manifest.ManifestFixtures.REPO_WORKER_ROOT;
import static dev.testgrid.api.manifest.ManifestFixtures.appendToSpec;
import static dev.testgrid.api.manifest.ManifestFixtures.copyOfRepoWorker;
import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.manifest.ManifestSeeder.SeededSuite;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PRD-02 FR-2 against real PostgreSQL. The context's own startup already seeded once. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class ManifestSeedingTest {

  @Autowired private ManifestSeeder seeder;
  @Autowired private ManifestLoader loader;
  @Autowired private JdbcClient jdbc;
  @TempDir Path tmp;

  @AfterEach
  void restoreRepositoryManifest() {
    seeder.seed(LocalProject.ID, loader.load(REPO_WORKER_ROOT));
  }

  @Test
  void startupSeedsTheCurrentRevisionWithItsCases() {
    String revision = loader.load(REPO_WORKER_ROOT).getFirst().revision();

    Map<String, Object> suite =
        jdbc.sql(
                "SELECT id, revision, display_name, timeout_ms, playwright_retries,"
                    + " array_to_string(allowed_engines, ',') AS engines"
                    + " FROM suites WHERE project_id = :p AND suite_key = 'checkout-smoke' AND is_current")
            .param("p", LocalProject.ID)
            .query()
            .singleRow();
    assertThat(suite)
        .containsEntry("revision", revision)
        .containsEntry("display_name", "Checkout smoke")
        .containsEntry("timeout_ms", 120000)
        .containsEntry("playwright_retries", 1)
        .containsEntry("engines", "chromium,firefox,webkit");

    List<String> critical =
        jdbc.sql(
                "SELECT case_key FROM test_cases WHERE suite_id = :s AND critical ORDER BY case_key")
            .param("s", suite.get("id"))
            .query(String.class)
            .list();
    assertThat(critical).containsExactly("cart-total", "checkout-happy-path");
    assertThat(caseCount(suite.get("id"))).isEqualTo(6);
  }

  @Test
  void reseedingAnUnchangedManifestChangesNothing() {
    long suitesBefore = count("SELECT count(*) FROM suites");
    long casesBefore = count("SELECT count(*) FROM test_cases");

    List<SeededSuite> again = seeder.seed(LocalProject.ID, loader.load(REPO_WORKER_ROOT));

    assertThat(again).singleElement().extracting(SeededSuite::created).isEqualTo(false);
    assertThat(count("SELECT count(*) FROM suites")).isEqualTo(suitesBefore);
    assertThat(count("SELECT count(*) FROM test_cases")).isEqualTo(casesBefore);
  }

  @Test
  void aChangedRevisionGetsANewRowAndTheOldOneIsKept() {
    SeededSuite original = seeder.seed(LocalProject.ID, loader.load(REPO_WORKER_ROOT)).getFirst();
    Path changed = copyOfRepoWorker(tmp);
    appendToSpec(changed, "// a new line changes the spec bytes\n");

    SeededSuite updated = seeder.seed(LocalProject.ID, loader.load(changed)).getFirst();

    assertThat(updated.created()).isTrue();
    assertThat(updated.revision()).isNotEqualTo(original.revision());
    assertThat(currentRevision()).isEqualTo(updated.revision());
    assertThat(isCurrent(original.suiteId())).isFalse();
    assertThat(caseCount(original.suiteId())).isEqualTo(6);
    assertThat(caseCount(updated.suiteId())).isEqualTo(6);
  }

  @Test
  void aSuiteRemovedFromTheManifestIsNoLongerCurrent() {
    seeder.seed(LocalProject.ID, List.of());

    assertThat(count("SELECT count(*) FROM suites WHERE is_current")).isZero();
    assertThat(count("SELECT count(*) FROM suites WHERE suite_key = 'checkout-smoke'"))
        .isPositive();
  }

  @Test
  void concurrentSeedingOfANewRevisionCreatesItOnce() throws Exception {
    Path changed = copyOfRepoWorker(tmp);
    appendToSpec(changed, "// concurrent seed\n");
    List<LoadedSuite> suites = loader.load(changed);

    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    Callable<SeededSuite> task =
        () -> {
          start.await();
          return seeder.seed(LocalProject.ID, suites).getFirst();
        };
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<SeededSuite>> results =
          IntStream.range(0, threads).mapToObj(i -> pool.submit(task)).toList();
      start.countDown();
      long created = 0;
      for (Future<SeededSuite> f : results) {
        created += f.get().created() ? 1 : 0;
      }
      assertThat(created).isEqualTo(1);
    }
    assertThat(currentRevision()).isEqualTo(suites.getFirst().revision());
  }

  private String currentRevision() {
    return jdbc.sql(
            "SELECT revision FROM suites WHERE project_id = :p AND suite_key = 'checkout-smoke'"
                + " AND is_current")
        .param("p", LocalProject.ID)
        .query(String.class)
        .single();
  }

  private boolean isCurrent(Object suiteId) {
    return jdbc.sql("SELECT is_current FROM suites WHERE id = :id")
        .param("id", suiteId)
        .query(Boolean.class)
        .single();
  }

  private long caseCount(Object suiteId) {
    return jdbc.sql("SELECT count(*) FROM test_cases WHERE suite_id = :s")
        .param("s", suiteId)
        .query(Long.class)
        .single();
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
