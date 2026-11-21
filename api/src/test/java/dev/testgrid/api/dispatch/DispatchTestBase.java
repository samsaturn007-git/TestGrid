package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.internal.AttemptService;
import dev.testgrid.api.internal.WorkerApi;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.run.CreateRunRequest;
import dev.testgrid.api.run.RunCreationService;
import dev.testgrid.api.support.PostgresTestcontainer;
import dev.testgrid.api.support.RabbitTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Testcontainers PostgreSQL and RabbitMQ with the background loops off: each test drives {@link
 * Dispatcher} and {@link OutboxPublisher} tick by tick. Subclasses share one context.
 */
@SpringBootTest(properties = "testgrid.profile=local")
@Import({PostgresTestcontainer.class, RabbitTestcontainer.class})
abstract class DispatchTestBase {

  static final List<String> QUEUES =
      List.of(
          "tg.targets.chromium",
          "tg.targets.firefox",
          "tg.targets.webkit",
          Topology.RETRY_QUEUE,
          Topology.DEAD_QUEUE);

  @Autowired JdbcClient jdbc;
  @Autowired Dispatcher dispatcher;
  @Autowired OutboxPublisher publisher;
  @Autowired FaultInjector faults;
  @Autowired RunCreationService runCreation;
  @Autowired AttemptService attempts;
  @Autowired RabbitAdmin admin;

  @BeforeEach
  void cleanState() {
    // TRUNCATE skips the row-level guards on run_events and outbox_events.
    jdbc.sql("TRUNCATE outbox_events, case_results, attempts, run_events, run_targets, runs")
        .update();
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 3").update();
    faults.disarm();
    admin.initialize();
    QUEUES.forEach(queue -> admin.purgeQueue(queue, false));
  }

  @AfterEach
  void restoreSettings() {
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 3").update();
    faults.disarm();
  }

  /** One run with one target per engine; returns the run ID. */
  UUID createRun(Engine... engines) {
    var targets =
        java.util.Arrays.stream(engines)
            .map(engine -> new CreateRunRequest.Target(Executor.LOCAL, engine))
            .toList();
    var request =
        new CreateRunRequest(
            "checkout-smoke",
            targets,
            Trigger.MANUAL,
            UUID.randomUUID().toString(),
            null,
            null,
            null);
    return runCreation.create(LocalProject.ID, request).run().id();
  }

  UUID targetOf(UUID runId) {
    return jdbc.sql("SELECT id FROM run_targets WHERE run_id = :r")
        .param("r", runId)
        .query(UUID.class)
        .single();
  }

  /** The PRD-03 claim path; the worker moves to the AMQP claim in T3 (CONFLICT_REGISTER N11). */
  WorkerApi.ClaimResponse claim() {
    return attempts
        .claimNext(new WorkerApi.ClaimRequest("w-test", Executor.LOCAL, List.of(Engine.CHROMIUM)))
        .orElseThrow();
  }

  void completeAsInfraError(WorkerApi.ClaimResponse claim) {
    attempts.complete(
        claim.attemptId(),
        new WorkerApi.CompletionRequest(
            claim.leaseToken(),
            "attempt-" + claim.attemptId() + "-final",
            WorkerApi.CompletionStatus.INFRA_ERROR,
            List.of(),
            new WorkerApi.WorkerError(ErrorClass.BROWSER_LAUNCH, "test")));
  }

  long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
