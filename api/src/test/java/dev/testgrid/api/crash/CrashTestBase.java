package dev.testgrid.api.crash;

import dev.testgrid.api.dispatch.FaultInjector;
import dev.testgrid.api.dispatch.Topology;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.internal.AttemptService;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.run.CreateRunRequest;
import dev.testgrid.api.run.RunCreationService;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * PRD-04 T6: the whole dispatch path with its background loops running, against Testcontainers
 * PostgreSQL and RabbitMQ, driven by {@link SimWorker}s. Timings are shortened so a crash test
 * takes seconds: lease TTL 5 s, heartbeat 1 s, reaper every 200 ms, retry backoff 200 ms × 2ⁿ⁻¹
 * (max 1 s), dispatch timeout 2 s. Every subclass shares one context, so one database and one
 * broker.
 */
@SpringBootTest(
    properties = {
      "testgrid.profile=local",
      "testgrid.dispatch.enabled=true",
      "testgrid.dispatch.tick-ms=50",
      "testgrid.dispatch.outbox-poll-ms=50",
      "testgrid.dispatch.confirm-timeout-ms=2000",
      "testgrid.dispatch.publish-backoff-max-ms=1000",
      "testgrid.reaper.interval-ms=200",
      "testgrid.reaper.retry-backoff-base-ms=200",
      "testgrid.reaper.retry-backoff-max-ms=1000",
      "testgrid.reaper.dispatch-timeout-ms=2000",
      "testgrid.internal.heartbeat-interval-seconds=1"
    })
@Import({PostgresTestcontainer.class, CrashRabbit.class})
@ExtendWith(EvidenceRecorder.class)
abstract class CrashTestBase {

  static final int LEASE_TTL_S = 5;
  static final int MAX_ATTEMPTS = 3;
  static final long MAX_BACKOFF_MS = 1000;
  static final List<String> ALL_ENGINES = List.of("chromium", "firefox", "webkit");

  @Autowired JdbcClient jdbc;
  @Autowired RunCreationService runCreation;
  @Autowired AttemptService attempts;
  @Autowired RabbitAdmin admin;
  @Autowired RabbitMQContainer rabbit;
  @Autowired FaultInjector faults;

  final List<SimWorker> workers = new ArrayList<>();

  @BeforeEach
  void cleanState() {
    jdbc.sql(
            "TRUNCATE workers, outbox_events, artifacts, attempt_heartbeats, case_results, attempts, failure_fingerprints, audit_events, run_events, run_targets,"
                + " runs")
        .update();
    jdbc.sql(
            "UPDATE scheduler_settings SET global_max_sessions = 3, max_attempts = :m,"
                + " lease_ttl_s = :ttl")
        .param("m", MAX_ATTEMPTS)
        .param("ttl", LEASE_TTL_S)
        .update();
    faults.disarm();
    await(Duration.ofSeconds(60), this::brokerUp, "broker reachable");
    for (String queue : queues()) {
      admin.purgeQueue(queue, false);
    }
  }

  @AfterEach
  void stopWorkers() {
    workers.forEach(SimWorker::close);
    workers.clear();
    faults.disarm();
  }

  static List<String> queues() {
    List<String> out = new ArrayList<>();
    ALL_ENGINES.forEach(e -> out.add("tg.targets." + e));
    out.add(Topology.RETRY_QUEUE);
    out.add(Topology.DEAD_QUEUE);
    return out;
  }

  /** True once the API's own connection can declare the topology (after a broker restart). */
  boolean brokerUp() {
    try {
      admin.initialize();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  SimWorker worker(String name, int slots, long runMs, List<String> engines) {
    SimWorker w =
        new SimWorker(
            name,
            attempts,
            rabbit.getHost(),
            rabbit.getAmqpPort(),
            rabbit.getAdminUsername(),
            rabbit.getAdminPassword(),
            slots,
            runMs,
            engines);
    workers.add(w);
    return w;
  }

  /** One run with one target per engine. */
  UUID createRun(String... engines) {
    var targets =
        Arrays.stream(engines)
            .map(e -> new CreateRunRequest.Target(Executor.LOCAL, Engine.fromWireName(e)))
            .toList();
    return runCreation
        .create(
            LocalProject.ID,
            new CreateRunRequest(
                "checkout-smoke",
                targets,
                Trigger.CHAOS,
                UUID.randomUUID().toString(),
                null,
                null,
                null))
        .run()
        .id();
  }

  UUID onlyTarget(UUID runId) {
    return jdbc.sql("SELECT id FROM run_targets WHERE run_id = :r")
        .param("r", runId)
        .query(UUID.class)
        .single();
  }

  String targetStatus(UUID targetId) {
    return jdbc.sql("SELECT status FROM run_targets WHERE id = :t")
        .param("t", targetId)
        .query(String.class)
        .single();
  }

  String runStatus(UUID runId) {
    return jdbc.sql("SELECT status FROM runs WHERE id = :r")
        .param("r", runId)
        .query(String.class)
        .single();
  }

  List<String> attemptsOf(UUID targetId) {
    return jdbc.sql(
            "SELECT attempt_no || ':' || status || ':' || coalesce(error_class, '-')"
                + " FROM attempts WHERE target_id = :t ORDER BY attempt_no")
        .param("t", targetId)
        .query(String.class)
        .list();
  }

  long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  /** Messages ready in the queue (unacked deliveries are not counted). */
  int depth(String queue) {
    var info = admin.getQueueInfo(queue);
    return info == null ? -1 : (int) info.getMessageCount();
  }

  boolean terminal(UUID targetId) {
    return List.of("PASSED", "FAILED", "TIMED_OUT", "INFRA_ERROR", "CANCELLED")
        .contains(targetStatus(targetId));
  }

  static void await(Duration limit, BooleanSupplier condition, String what) {
    long end = System.nanoTime() + limit.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out after " + limit + " waiting for: " + what);
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
