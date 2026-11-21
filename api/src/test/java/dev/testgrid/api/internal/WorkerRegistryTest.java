package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.worker.WorkerRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PRD-04 FR-7: register, heartbeat, drain, LOST, and {@code GET /api/v1/workers}. */
class WorkerRegistryTest extends WorkerApiTestBase {

  @Autowired WorkerRegistry registry;

  @Test
  void aWorkerRegistersHeartbeatsAndShowsUpWithItsSlotUsage() {
    var registered = register("worker-abc123", 2);
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    String id = JsonPath.read(registered.body(), "$.id");
    assertThat(JsonPath.<String>read(registered.body(), "$.state")).isEqualTo("ACTIVE");
    assertThat(JsonPath.<Integer>read(registered.body(), "$.heartbeatIntervalSeconds"))
        .isEqualTo(10);

    jdbc.sql("UPDATE workers SET last_seen_at = now() - interval '20 seconds'").update();
    var beat = internalPost("/internal/v1/workers/" + id + "/heartbeat", "{}");
    assertThat(beat.statusCode()).isEqualTo(200);
    assertThat(count("SELECT count(*) FROM workers WHERE last_seen_at > now() - interval '5 s'"))
        .isEqualTo(1);

    createRun("checkout-smoke", "PASS", "chromium");
    var claimed = claimResponse("worker-abc123", "chromium");
    assertThat(claimed.statusCode()).isEqualTo(200);

    var list = get(publicPort, "/api/v1/workers");
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(list.body(), "$.items[*].name"))
        .containsExactly("worker-abc123");
    assertThat(JsonPath.<Integer>read(list.body(), "$.items[0].activeAttempts")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(list.body(), "$.items[0].slots")).isEqualTo(2);
    assertThat(JsonPath.<List<String>>read(list.body(), "$.items[0].engines"))
        .containsExactly("chromium", "firefox");
    assertThat(JsonPath.<Integer>read(list.body(), "$.activeSessions")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(list.body(), "$.globalMaxSessions")).isEqualTo(3);
  }

  @Test
  void drainIsIdempotentAndVisible() {
    String id = JsonPath.read(register("worker-d", 1).body(), "$.id");
    for (int i = 0; i < 2; i++) {
      var drained = internalPost("/internal/v1/workers/" + id + "/drain", "{}");
      assertThat(drained.statusCode()).isEqualTo(200);
      assertThat(JsonPath.<String>read(drained.body(), "$.state")).isEqualTo("DRAINING");
    }
    assertThat(
            JsonPath.<String>read(
                internalPost("/internal/v1/workers/" + id + "/heartbeat", "{}").body(), "$.state"))
        .isEqualTo("DRAINING");
    assertThat(JsonPath.<String>read(get(publicPort, "/api/v1/workers").body(), "$.items[0].state"))
        .isEqualTo("DRAINING");
  }

  @Test
  void registeringAgainUnderTheSameNameReplacesTheOldRowWhichThenGets410() {
    String old = JsonPath.read(register("worker-r", 1).body(), "$.id");
    String fresh = JsonPath.read(register("worker-r", 1).body(), "$.id");
    assertThat(fresh).isNotEqualTo(old);
    assertThat(one("SELECT state FROM workers WHERE id = :id", old)).isEqualTo("LOST");
    assertThat(one("SELECT state FROM workers WHERE id = :id", fresh)).isEqualTo("ACTIVE");

    var stale = internalPost("/internal/v1/workers/" + old + "/heartbeat", "{}");
    assertThat(stale.statusCode()).isEqualTo(410);
    assertThat(stale.body()).contains("/problems/lease-expired").contains("register again");
    assertThat(internalPost("/internal/v1/workers/" + old + "/drain", "{}").statusCode())
        .isEqualTo(410);
  }

  @Test
  void aWorkerSilentForThreeTtlsBecomesLost() {
    String quiet = JsonPath.read(register("worker-quiet", 1).body(), "$.id");
    String alive = JsonPath.read(register("worker-alive", 1).body(), "$.id");
    // 3 × the 30 s lease TTL is 90 s.
    jdbc.sql("UPDATE workers SET last_seen_at = now() - interval '91 seconds' WHERE id = :id")
        .param("id", UUID.fromString(quiet))
        .update();
    jdbc.sql("UPDATE workers SET last_seen_at = now() - interval '89 seconds' WHERE id = :id")
        .param("id", UUID.fromString(alive))
        .update();

    assertThat(registry.sweep()).containsExactly("worker-quiet");
    assertThat(one("SELECT state FROM workers WHERE id = :id", quiet)).isEqualTo("LOST");
    assertThat(one("SELECT state FROM workers WHERE id = :id", alive)).isEqualTo("ACTIVE");
    assertThat(
            JsonPath.<List<String>>read(
                get(publicPort, "/api/v1/workers").body(), "$.items[*].state"))
        .containsExactly("ACTIVE", "LOST");
  }

  /** Review of PR #5: two registrations of one name at once used to race into a 500. */
  @org.junit.jupiter.api.RepeatedTest(5)
  void concurrentRegistrationsOfOneNameLeaveOneLiveRow() throws Exception {
    var go = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++) {
        futures.add(
            pool.submit(() -> {
              go.await();
              return register("worker-race", 1);
            }));
      }
      go.countDown();
      for (var f : futures) {
        assertThat(f.get().statusCode()).as(f.get().body()).isEqualTo(201);
      }
    }
    assertThat(count("SELECT count(*) FROM workers WHERE name = 'worker-race' AND state <> 'LOST'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM workers WHERE name = 'worker-race'")).isEqualTo(4);
  }

  @Test
  void badRegistrationsAre400AndUnknownWorkers404() {
    assertThat(post("w;rm -rf", "1.0", 1).statusCode()).isEqualTo(400);
    assertThat(post("worker-x", "1.0 beta", 1).statusCode()).isEqualTo(400);
    assertThat(post("worker-x", "1.0", 17).statusCode()).isEqualTo(400);
    assertThat(post("worker-x", "1.0", 0).statusCode()).isEqualTo(400);
    var unknown = internalPost("/internal/v1/workers/" + UUID.randomUUID() + "/heartbeat", "{}");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(count("SELECT count(*) FROM workers")).isZero();
  }

  private HttpResponse<String> register(String name, int slots) {
    return post(name, "dev", slots);
  }

  private HttpResponse<String> post(String name, String version, int slots) {
    return internalPost(
        "/internal/v1/workers/register",
        "{\"name\":\""
            + name
            + "\",\"version\":\""
            + version
            + "\",\"executor\":\"LOCAL\",\"engines\":[\"chromium\",\"firefox\"],\"slots\":"
            + slots
            + "}");
  }
}
