package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactProperties;
import dev.testgrid.api.artifact.ArtifactRepository;
import dev.testgrid.api.artifact.ArtifactRetention;
import dev.testgrid.api.artifact.ArtifactStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PRD-06 FR-6 / T6 against Testcontainers PostgreSQL and a real local store: expiry, purge, the
 * orphan sweeper in both directions, and what happens when a step fails half way (ART-002).
 */
class ArtifactRetentionTest extends WorkerApiTestBase {

  @Autowired ArtifactStore store;
  @Autowired ArtifactRetention retention;
  @Autowired ArtifactRepository artifactRows;

  Claim claim;
  final Path root = Path.of(ARTIFACT_ROOT);

  @BeforeEach
  void setUp() throws Exception {
    for (String key : store.keys()) {
      store.delete(key);
    }
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    claim = claim("chromium");
    start(claim);
  }

  /** Bytes in the store plus a row created 8 days ago, expiring `expiresIn` from now. */
  record Row(UUID id, String key) {}

  Row row(String expiresIn) throws Exception {
    return row(expiresIn, null);
  }

  Row row(String expiresIn, UUID fixedId) throws Exception {
    ArtifactStore.Stored bytes =
        store.put(
            new ByteArrayInputStream(ArtifactUploadTest.png(300)),
            new ArtifactStore.Meta(ArtifactKind.SCREENSHOT, Instant.now()));
    UUID id =
        jdbc.sql(
                "INSERT INTO artifacts (id, attempt_id, case_key, kind, mime, storage_key, byte_size,"
                    + " sha256, expires_at, created_at) VALUES (coalesce(:id, gen_random_uuid()), :a, 'cart-total', 'SCREENSHOT',"
                    + " 'image/png', :k, :s, :sha, now() + CAST(:exp AS interval),"
                    + " now() - interval '8 days') RETURNING id")
            .param("id", fixedId)
            .param("a", UUID.fromString(claim.attemptId()))
            .param("k", bytes.key())
            .param("s", bytes.byteSize())
            .param("sha", bytes.sha256())
            .param("exp", expiresIn)
            .query(UUID.class)
            .single();
    return new Row(id, bytes.key());
  }

  String state(Row row) {
    return one("SELECT state FROM artifacts WHERE id = :id", row.id());
  }

  void age(String key, Duration by) throws Exception {
    Files.setLastModifiedTime(root.resolve(key), FileTime.from(Instant.now().minus(by)));
  }

  int linkStatus(Row row) {
    return post(publicPort, "/api/v1/artifacts/" + row.id() + "/links", "", null).statusCode();
  }

  @Test
  void art002AnExpiredArtifactIsExpiredThenPurgedAndItsBytesDeleted() throws Exception {
    Row expired = row("-1 minute");
    Row fresh = row("7 days");

    var result = retention.purgeOnce();

    assertThat(result).isEqualTo(new ArtifactRetention.PurgeResult(1, 1, 0));
    assertThat(state(expired)).isEqualTo("PURGED");
    assertThat(store.exists(expired.key())).isFalse();
    assertThat(state(fresh)).isEqualTo("AVAILABLE");
    assertThat(store.exists(fresh.key())).isTrue();
    assertThat(linkStatus(expired)).isEqualTo(410);
    assertThat(
            JsonPath.<String>read(
                get(publicPort, "/api/v1/artifacts/" + expired.id()).body(), "$.state"))
        .isEqualTo("PURGED");
    // Nothing left to do: a second pass changes nothing.
    assertThat(retention.purgeOnce()).isEqualTo(new ArtifactRetention.PurgeResult(0, 0, 0));
  }

  @Test
  void aFailedDeleteLeavesTheRowExpiredAndTheNextPassFinishesIt() throws Exception {
    Row expired = row("-1 minute");
    // A non-empty directory where the file was: the delete really fails.
    Path file = root.resolve(expired.key());
    Files.delete(file);
    Files.createDirectory(file);
    Files.writeString(file.resolve("blocker"), "x");

    assertThat(retention.purgeOnce()).isEqualTo(new ArtifactRetention.PurgeResult(1, 0, 1));
    assertThat(state(expired)).isEqualTo("EXPIRED");
    // Readers already get the expiry answer while the bytes wait for the next pass.
    assertThat(linkStatus(expired)).isEqualTo(410);

    Files.delete(file.resolve("blocker"));
    Files.delete(file);
    assertThat(retention.purgeOnce()).isEqualTo(new ArtifactRetention.PurgeResult(0, 1, 0));
    assertThat(state(expired)).isEqualTo("PURGED");
  }

  @Test
  void aCrashBetweenExpiringAndPurgingIsFinishedByTheNextPass() throws Exception {
    Row half = row("-1 minute");
    jdbc.sql("UPDATE artifacts SET state = 'EXPIRED' WHERE id = :id")
        .param("id", half.id())
        .update();

    assertThat(retention.purgeOnce()).isEqualTo(new ArtifactRetention.PurgeResult(0, 1, 0));
    assertThat(state(half)).isEqualTo("PURGED");
    assertThat(store.exists(half.key())).isFalse();
  }

  @Test
  void theSweeperDeletesOldOrphanFilesButNotYoungOnesOrFilesWithRows() throws Exception {
    Row kept = row("7 days");
    age(kept.key(), Duration.ofHours(3));
    String oldOrphan =
        store
            .put(
                new ByteArrayInputStream("old".getBytes()),
                new ArtifactStore.Meta(ArtifactKind.LOG, Instant.now()))
            .key();
    age(oldOrphan, Duration.ofHours(2));
    // An upload's file exists for a moment before its row: young files are never orphans.
    String youngOrphan =
        store
            .put(
                new ByteArrayInputStream("new".getBytes()),
                new ArtifactStore.Meta(ArtifactKind.LOG, Instant.now()))
            .key();

    var result = retention.sweepOnce();

    assertThat(result.orphanFilesDeleted()).isEqualTo(1);
    assertThat(store.exists(oldOrphan)).isFalse();
    assertThat(store.exists(youngOrphan)).isTrue();
    assertThat(store.exists(kept.key())).isTrue();
    assertThat(state(kept)).isEqualTo("AVAILABLE");
  }

  @Test
  void art002ARowWhoseBytesAreGoneBecomesMissingAndReads404() throws Exception {
    Row gone = row("7 days");
    Row present = row("7 days");
    store.delete(gone.key());

    var result = retention.sweepOnce();

    assertThat(result.rowsMarkedMissing()).isEqualTo(1);
    assertThat(state(gone)).isEqualTo("MISSING");
    assertThat(state(present)).isEqualTo("AVAILABLE");
    assertThat(linkStatus(gone)).isEqualTo(404);
    assertThat(retention.sweepOnce()).isEqualTo(new ArtifactRetention.SweepResult(0, 0, 0));
  }

  @Test
  void theSweeperRemovesAbandonedPartialUploadsOnly() throws Exception {
    Path incoming = root.resolve(".incoming");
    Path stale = Files.writeString(incoming.resolve(UUID.randomUUID() + ".part"), "half an upload");
    Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    Path live = Files.writeString(incoming.resolve(UUID.randomUUID() + ".part"), "uploading now");

    assertThat(retention.sweepOnce().partsDeleted()).isEqualTo(1);
    assertThat(stale).doesNotExist();
    assertThat(live).exists();
    Files.delete(live);
  }

  @Test
  void everyStepPagesThroughMoreRowsThanOneBatch() throws Exception {
    // A batch of one, so the expire, purge and missing-check loops each need several pages.
    ArtifactRetention oneAtATime =
        new ArtifactRetention(
            artifactRows,
            store,
            new ArtifactProperties(
                root, Duration.ofDays(7), null, 300, Duration.ofHours(1), Duration.ofHours(1), 1),
            jdbc);
    Row e1 = row("-1 minute");
    Row e2 = row("-2 minutes");
    Row e3 = row("-3 minutes");
    Row m1 = row("7 days");
    Row m2 = row("7 days");
    row("7 days");
    store.delete(m1.key());
    store.delete(m2.key());

    assertThat(oneAtATime.purgeOnce()).isEqualTo(new ArtifactRetention.PurgeResult(3, 3, 0));
    assertThat(oneAtATime.sweepOnce().rowsMarkedMissing()).isEqualTo(2);
    for (Row r : java.util.List.of(e1, e2, e3)) {
      assertThat(state(r)).isEqualTo("PURGED");
    }
  }

  @Test
  void theMissingCheckVisitsIdsFromTheLowAndHighHalvesAlike() throws Exception {
    // Regression: a scan starting after 8000… (Java's signed UUID minimum) skipped ids 0…–7….
    Row low = row("7 days", UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"));
    Row high = row("7 days", UUID.fromString("f08fad5b-d9cb-469f-a165-70867728950e"));
    store.delete(low.key());
    store.delete(high.key());

    assertThat(retention.sweepOnce().rowsMarkedMissing()).isEqualTo(2);
    assertThat(state(low)).isEqualTo("MISSING");
    assertThat(state(high)).isEqualTo("MISSING");
  }
}
