package dev.testgrid.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** PRD-06 FR-6: the background loop really runs the retention passes (here every second). */
@SpringBootTest(
    properties = {
      "testgrid.artifacts.retention-enabled=true",
      "testgrid.artifacts.purge-interval=1s",
      "testgrid.artifacts.root=target/test-artifacts/retention-loop"
    })
@Import(PostgresTestcontainer.class)
class ArtifactRetentionLoopTest {

  @Autowired ArtifactRetentionLoop loop;
  @Autowired ArtifactStore store;

  @Test
  void theLoopSweepsAnAbandonedPartialUpload() throws Exception {
    assertThat(loop.isRunning()).isTrue();
    Path part =
        ((LocalFsArtifactStore) store)
            .root()
            .resolve(".incoming")
            .resolve(UUID.randomUUID() + ".part");
    Files.writeString(part, "a crashed upload");
    Files.setLastModifiedTime(part, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

    Instant deadline = Instant.now().plusSeconds(15);
    while (Files.exists(part) && Instant.now().isBefore(deadline)) {
      Thread.sleep(200);
    }
    assertThat(part).doesNotExist();
  }
}
