package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;

/**
 * PRD-04 FR-5 through real HTTP: concurrent claims of dispatch messages. The row lock makes a
 * duplicate delivery of one message claim its target once; the dispatcher's advisory lock, taken by
 * every claim, makes the capacity re-check hold under concurrency (invariant I5).
 */
class ClaimConcurrencyTest extends WorkerApiTestBase {

  @RepeatedTest(3)
  void twoConcurrentClaimsOfOneMessageClaimTheTargetOnce() throws Exception {
    createRun("checkout-smoke", "PASS", "chromium");
    Dispatched message = dispatchOldest("chromium");

    List<HttpResponse<String>> responses =
        concurrently(2, i -> claimTarget("w-" + i, message.targetId(), message.generation()));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_CLAIMED'")).isEqualTo(1);
  }

  @RepeatedTest(3)
  void tenConcurrentClaimsForFourMessagesClaimEachTargetOnce() throws Exception {
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 4").update();
    createRun("checkout-smoke", "PASS", "chromium", "firefox", "webkit");
    createRun("catalog-regression", "PASS", "chromium");
    List<Dispatched> messages = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      messages.add(dispatchOldest("chromium", "firefox", "webkit"));
    }

    // Each message is delivered to between two and three workers at once.
    List<HttpResponse<String>> responses =
        concurrently(
            10,
            i -> {
              Dispatched m = messages.get(i % 4);
              return claimTarget("w-" + i, m.targetId(), m.generation());
            });

    assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isIn(200, 409));
    assertThat(responses).filteredOn(r -> r.statusCode() == 200).hasSize(4);
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(4);
    assertThat(count("SELECT count(DISTINCT target_id) FROM attempts")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM run_targets WHERE status = 'CLAIMED'")).isEqualTo(4);
  }

  /**
   * Two messages are out while the cap is 1 (the cap was lowered after dispatch). Without the
   * advisory lock both claims could count zero active attempts and both succeed.
   */
  @RepeatedTest(3)
  void concurrentClaimsNeverGoOverTheCap() throws Exception {
    createRun("checkout-smoke", "PASS", "chromium", "firefox");
    Dispatched first = dispatchOldest("chromium", "firefox");
    Dispatched second = dispatchOldest("chromium", "firefox");
    jdbc.sql("UPDATE scheduler_settings SET global_max_sessions = 1").update();

    List<HttpResponse<String>> responses =
        concurrently(
            2,
            i -> {
              Dispatched m = i == 0 ? first : second;
              return claimTarget("w-" + i, m.targetId(), m.generation());
            });

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 429);
    assertThat(count("SELECT count(*) FROM attempts WHERE status = 'CLAIMED'")).isEqualTo(1);
  }

  interface Call {
    HttpResponse<String> run(int index) throws Exception;
  }

  private List<HttpResponse<String>> concurrently(int threads, Call call) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int index = i;
        Callable<HttpResponse<String>> task =
            () -> {
              go.await();
              return call.run(index);
            };
        futures.add(pool.submit(task));
      }
      go.countDown();
      List<HttpResponse<String>> out = new ArrayList<>();
      for (var f : futures) {
        out.add(f.get());
      }
      return out;
    }
  }
}
