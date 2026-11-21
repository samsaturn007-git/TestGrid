package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-3: concurrent {@code claims/next} calls through real HTTP. SKIP LOCKED means each target is
 * claimed exactly once and the losers move on (or get 204) instead of waiting or failing.
 */
class ClaimConcurrencyTest extends WorkerApiTestBase {

  @Autowired DataSource dataSource;

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

  /**
   * What SKIP LOCKED itself buys: a claimer doesn't wait for a row another transaction holds, it
   * takes the next one. Without SKIP LOCKED this claim blocks until the lock is released (each
   * target is still claimed once either way; the database re-checks the row after the wait).
   */
  @Test
  void aClaimSkipsATargetLockedByAnotherTransactionInsteadOfWaiting() throws Exception {
    String older = createRun("checkout-smoke", "PASS", "chromium");
    String newer = createRun("checkout-smoke", "PASS", "chromium");
    ExecutorService one = Executors.newSingleThreadExecutor();
    HttpResponse<String> response;
    long millis;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT id FROM run_targets WHERE run_id = ? FOR UPDATE")) {
        lock.setObject(1, UUID.fromString(older));
        lock.executeQuery().close();
      }
      long started = System.nanoTime();
      Future<HttpResponse<String>> claim = one.submit(() -> claimResponse("w-skip", "chromium"));
      try {
        // Without SKIP LOCKED the claim waits for this transaction and times out here.
        response = claim.get(5, TimeUnit.SECONDS);
      } finally {
        // Release the lock before anything waits on the claim, so a failure can't hang.
        holder.rollback();
      }
      millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    } finally {
      one.shutdownNow();
    }
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.runId")).isEqualTo(newer);
    assertThat(millis).as("did not wait for the locked row").isLessThan(2000);
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
