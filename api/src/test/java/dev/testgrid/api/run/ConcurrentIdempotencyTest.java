package dev.testgrid.api.run;

import static dev.testgrid.api.run.RunTestSupport.RUNS_URL;
import static dev.testgrid.api.run.RunTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * API-004: parallel POSTs with one idempotency key, through the real HTTP server and connection
 * pool. Repeated so the race is exercised more than once per build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class ConcurrentIdempotencyTest {

  private static final int REQUESTS = 10;

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
  }

  private record Reply(int status, String runId, String replayHeader) {}

  @RepeatedTest(3)
  void api004TenConcurrentIdenticalRequestsCreateExactlyOneRun() throws Exception {
    String body = body(UUID.randomUUID().toString(), "chromium");
    List<Reply> replies = fire(i -> body);

    assertThat(replies).extracting(Reply::status).filteredOn(s -> s == 202).hasSize(1);
    assertThat(replies).extracting(Reply::status).filteredOn(s -> s == 200).hasSize(REQUESTS - 1);
    assertThat(replies)
        .filteredOn(r -> r.status() == 200)
        .extracting(Reply::replayHeader)
        .containsOnly("true");
    assertThat(replies.stream().map(Reply::runId).distinct()).hasSize(1);
    assertThat(count(jdbc, "runs")).isEqualTo(1);
    assertThat(count(jdbc, "run_targets")).isEqualTo(1);
    assertThat(count(jdbc, "run_events")).isEqualTo(1);
  }

  @RepeatedTest(3)
  void api004ConcurrentRequestsWithDifferentBodiesCreateOneRunAndConflictTheRest()
      throws Exception {
    String key = UUID.randomUUID().toString();
    List<Reply> replies = fire(i -> body(key, i % 2 == 0 ? "chromium" : "firefox"));

    List<Integer> statuses = replies.stream().map(Reply::status).toList();
    assertThat(statuses).containsOnly(202, 200, 409);
    assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
    // The winner's 5 identical bodies get 202 + 4 replays; the other 5 bodies get 409.
    assertThat(statuses).filteredOn(s -> s == 200).hasSize(4);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
    assertThat(count(jdbc, "runs")).isEqualTo(1);
  }

  private interface BodyForRequest {
    String body(int index);
  }

  private List<Reply> fire(BodyForRequest bodies) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Reply>> calls = new ArrayList<>();
    for (int i = 0; i < REQUESTS; i++) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + RUNS_URL))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(bodies.body(i)))
              .build();
      calls.add(() -> {
        start.await();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String runId = response.statusCode() < 300 ? JsonPath.read(response.body(), "$.id") : null;
        return new Reply(
            response.statusCode(),
            runId,
            response.headers().firstValue(RunController.REPLAY_HEADER).orElse(null));
      });
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
      List<Future<Reply>> futures = new ArrayList<>();
      for (Callable<Reply> call : calls) {
        futures.add(pool.submit(call));
      }
      start.countDown();
      List<Reply> replies = new ArrayList<>();
      Set<Integer> unexpected = new HashSet<>();
      for (Future<Reply> f : futures) {
        Reply r = f.get();
        replies.add(r);
        if (r.status() >= 500) {
          unexpected.add(r.status());
        }
      }
      assertThat(unexpected).as("no request may fail with a server error").isEmpty();
      return replies;
    }
  }

  private static String body(String key, String engine) {
    return "{\"suiteKey\":\"checkout-smoke\",\"targets\":[{\"executor\":\"LOCAL\",\"engine\":\""
        + engine
        + "\"}],\"trigger\":\"CI\",\"idempotencyKey\":\""
        + key
        + "\"}";
  }
}
