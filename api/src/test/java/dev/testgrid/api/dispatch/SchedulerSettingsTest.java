package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.SchedulerPolicy;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.run.CreateRunRequest;
import dev.testgrid.api.run.RunCreationService;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-04 T1 / CONFLICT_REGISTER N15: {@code scheduler_settings} is the one source for the cap, the
 * retry budget and the lease TTL; properties only seed it. Each test is rolled back.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Transactional
class SchedulerSettingsTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private SchedulerSettingsRepository settings;
  @Autowired private RunCreationService runCreation;

  @Test
  void startupSeedsTheRowFromTheDocumentedDefaults() {
    // ARCHITECTURE §6: lease TTL 30 s, max attempts 3; the cap of 3 matches PRD-04's demo.
    assertThat(settings.current())
        .isEqualTo(new SchedulerSettings(3, SchedulerPolicy.FIFO, 0.0, 3, 30));
  }

  @Test
  void theDatabaseRowWinsOverTheProperties() {
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 5, lease_ttl_s = 45").update();
    assertThat(settings.current().globalMaxSessions()).isEqualTo(5);
    assertThat(settings.current().leaseTtlSeconds()).isEqualTo(45);
  }

  @Test
  void aMissingRowIsSeededAgainOnRead() {
    jdbc.sql("DELETE FROM scheduler_settings").update();
    assertThat(settings.current().maxAttempts()).isEqualTo(3);
    assertThat(jdbc.sql("SELECT count(*) FROM scheduler_settings").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void aLeaseTtlNotLongerThanTheHeartbeatIsRefused() {
    jdbc.sql("UPDATE scheduler_settings SET lease_ttl_s = 10").update();
    assertThatThrownBy(settings::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be longer than the heartbeat interval");
  }

  @Test
  void newTargetsSnapshotMaxAttemptsFromTheRow() {
    jdbc.sql("UPDATE scheduler_settings SET max_attempts = 5").update();
    var request =
        new CreateRunRequest(
            "checkout-smoke",
            List.of(new CreateRunRequest.Target(Executor.LOCAL, Engine.CHROMIUM)),
            Trigger.MANUAL,
            "settings-" + UUID.randomUUID(),
            null,
            null,
            null);
    UUID runId = runCreation.create(LocalProject.ID, request).run().id();

    jdbc.sql("UPDATE scheduler_settings SET max_attempts = 2").update();
    assertThat(
            jdbc.sql("SELECT max_attempts FROM run_targets WHERE run_id = :r")
                .param("r", runId)
                .query(Integer.class)
                .single())
        .isEqualTo(5);
  }
}
