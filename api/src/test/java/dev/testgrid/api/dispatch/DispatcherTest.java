package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.Engine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PRD-04 FR-2 / T2: the capacity-aware FIFO dispatcher (ARCHITECTURE §3, ADR-004). */
class DispatcherTest extends DispatchTestBase {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void releasesAtMostTheCapInFifoOrder() {
    List<UUID> runs = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      runs.add(createRun(Engine.CHROMIUM));
    }

    var released = dispatcher.dispatchOnce();

    // cap 3, nothing active: free = 3 − 0 − 0, and the three oldest go first.
    assertThat(released)
        .extracting(Dispatcher.Dispatched::runId)
        .containsExactlyElementsOf(runs.subList(0, 3));
    assertThat(released).allSatisfy(d -> assertThat(d.dispatchGeneration()).isEqualTo(1));
    // Dispatch is not a status change: the targets stay QUEUED until a worker claims them.
    assertThat(
            count(
                "SELECT count(*) FROM run_targets WHERE status = 'QUEUED'"
                    + " AND dispatched_at IS NOT NULL AND dispatch_generation = 1"))
        .isEqualTo(3);
    assertThat(count("SELECT count(*) FROM run_targets WHERE dispatched_at IS NULL")).isEqualTo(2);

    // free = 3 − 0 − 3 dispatched-but-unclaimed = 0.
    assertThat(dispatcher.dispatchOnce()).isEmpty();
  }

  @Test
  void writesTheOutboxRowAndTheRunEventInTheSameTransaction() throws Exception {
    UUID runId = createRun(Engine.CHROMIUM);
    UUID targetId = targetOf(runId);

    var dispatched = dispatcher.dispatchOnce().getFirst();

    Map<String, Object> outbox =
        jdbc.sql(
                "SELECT aggregate_type, aggregate_id, event_type, schema_version, payload::text AS"
                    + " payload, traceparent, published_at, publish_attempts FROM outbox_events")
            .query()
            .singleRow();
    assertThat(outbox)
        .containsEntry("aggregate_type", "run_target")
        .containsEntry("aggregate_id", targetId)
        .containsEntry("event_type", "TARGET_DISPATCHED")
        .containsEntry("schema_version", 1)
        .containsEntry("publish_attempts", 0);
    assertThat(outbox.get("published_at")).isNull();
    assertThat((String) outbox.get("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");

    // Contracts §9 body, exactly these four fields.
    JsonNode body = mapper.readTree((String) outbox.get("payload"));
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("messageId", "targetId", "dispatchGeneration", "schemaVersion");
    assertThat(body.get("messageId").asString()).isEqualTo(dispatched.messageId().toString());
    assertThat(body.get("targetId").asString()).isEqualTo(targetId.toString());
    assertThat(body.get("dispatchGeneration").asInt()).isEqualTo(1);
    assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);

    String event =
        jdbc.sql(
                "SELECT payload::text FROM run_events WHERE type = 'TARGET_DISPATCHED'"
                    + " AND target_id = :t")
            .param("t", targetId)
            .query(String.class)
            .single();
    JsonNode data = mapper.readTree(event);
    assertThat(data.get("messageId").asString()).isEqualTo(dispatched.messageId().toString());
    assertThat(data.get("dispatchGeneration").asInt()).isEqualTo(1);
    assertThat(data.get("engine").asString()).isEqualTo("chromium");
  }

  @Test
  void aClaimKeepsTheSlotAndACompletionFreesIt() {
    for (int i = 0; i < 4; i++) {
      createRun(Engine.CHROMIUM);
    }
    assertThat(dispatcher.dispatchOnce()).hasSize(3);

    // The claim moves one target from dispatched-unclaimed to active: still 3 in use.
    var claim = claim();
    assertThat(count("SELECT count(*) FROM attempts WHERE status = 'CLAIMED'")).isEqualTo(1);
    assertThat(dispatcher.dispatchOnce()).isEmpty();

    // Completing it frees one slot, so exactly the fourth target is released.
    completeAsInfraError(claim);
    var released = dispatcher.dispatchOnce();
    assertThat(released).hasSize(1);
    assertThat(dispatcher.dispatchOnce()).isEmpty();
  }

  @Test
  void skipsTargetsThatAreNotDueCancelledOrOutOfAttempts() {
    UUID later = createRun(Engine.CHROMIUM);
    UUID cancelled = createRun(Engine.CHROMIUM);
    UUID exhausted = createRun(Engine.CHROMIUM);
    jdbc.sql("UPDATE run_targets SET not_before = now() + interval '1 hour' WHERE run_id = :r")
        .param("r", later)
        .update();
    jdbc.sql("UPDATE runs SET cancel_requested_at = now() WHERE id = :r")
        .param("r", cancelled)
        .update();
    jdbc.sql("UPDATE run_targets SET max_attempts = 1, attempt_count = 1 WHERE run_id = :r")
        .param("r", exhausted)
        .update();

    assertThat(dispatcher.dispatchOnce()).isEmpty();
    assertThat(count("SELECT count(*) FROM outbox_events")).isZero();

    // Once its backoff has passed, the delayed target is dispatched.
    jdbc.sql("UPDATE run_targets SET not_before = now() - interval '1 second' WHERE run_id = :r")
        .param("r", later)
        .update();
    assertThat(dispatcher.dispatchOnce())
        .extracting(Dispatcher.Dispatched::runId)
        .containsExactly(later);
  }

  @Test
  void followsTheCapInTheSettingsRow() {
    for (int i = 0; i < 3; i++) {
      createRun(Engine.CHROMIUM);
    }
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 1").update();

    assertThat(dispatcher.dispatchOnce()).hasSize(1);
    assertThat(dispatcher.dispatchOnce()).isEmpty();
  }

  @Test
  void concurrentTicksNeverSpendMoreThanTheCap() throws Exception {
    for (int i = 0; i < 10; i++) {
      createRun(Engine.CHROMIUM);
    }
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(() -> {
              go.await();
              return dispatcher.dispatchOnce().size();
            }));
      }
      go.countDown();
      int total = 0;
      for (Future<Integer> result : results) {
        total += result.get();
      }
      // The advisory lock serialises the ticks: the first spends the cap, the rest see free = 0.
      assertThat(total).isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM run_targets WHERE dispatched_at IS NOT NULL"))
        .isEqualTo(3);
    assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(3);
    assertThat(count("SELECT max(dispatch_generation) FROM run_targets")).isEqualTo(1);
  }
}
