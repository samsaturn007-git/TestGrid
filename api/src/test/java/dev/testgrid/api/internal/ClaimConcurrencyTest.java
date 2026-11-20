package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
 * FR-3: concurrent {@code claims/next} calls through real HTTP. SKIP LOCKED means each target is
 * claimed exactly once and the losers move on (or get 204) instead of waiting or failing.
 */
class ClaimConcurrencyTest extends WorkerApiTestBase {

  @RepeatedTest(3)
  void twoConcurrentClaimsForOneTargetClaimItOnce() throws Exception {
    createRun("checkout-smoke", "PASS", "chromium");
    List<HttpResponse<String>> responses = claimConcurrently(2);
    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 204);
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(1);
  }

  @RepeatedTest(3)
  void tenConcurrentClaimsForFourTargetsClaimEachOnce() throws Exception {
    createRun("checkout-smoke", "PASS", "chromium", "firefox", "webkit");
    createRun("catalog-regression", "PASS", "chromium");

    List<HttpResponse<String>> responses = claimConcurrently(10);

    List<String> claimedTargets = new ArrayList<>();
    for (var r : responses) {
      assertThat(r.statusCode()).as(r.body()).isIn(200, 204);
      if (r.statusCode() == 200) {
        claimedTargets.add(JsonPath.read(r.body(), "$.targetId"));
      }
    }
    assertThat(claimedTargets).hasSize(4).doesNotHaveDuplicates();
    assertThat(count("SELECT count(*) FROM attempts")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM run_targets WHERE status = 'CLAIMED'")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_CLAIMED'")).isEqualTo(4);
  }

  private List<HttpResponse<String>> claimConcurrently(int threads) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    Callable<HttpResponse<String>> task =
        () -> {
          go.await();
          return claimResponse(
              "w-" + Thread.currentThread().threadId(), "chromium", "firefox", "webkit");
        };
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
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
