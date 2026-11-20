package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-004, deterministic: a first transaction holds an uncommitted run with key K; a second request
 * for K passes the "key already used?" read (it can't see uncommitted rows) and reaches the INSERT.
 * The test waits until Postgres reports that INSERT blocked on a lock, then commits the first
 * transaction and checks how the second one resolves.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class IdempotencyRaceTest {

  @Autowired private RunCreationService service;
  @Autowired private RunRepository runs;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private JsonMapper mapper;

  @BeforeEach
  @AfterEach
  void clear() {
    RunTestSupport.clearRuns(jdbc);
  }

  private static CreateRunRequest request(String key, Engine engine) {
    return new CreateRunRequest(
        "checkout-smoke",
        List.of(new CreateRunRequest.Target(Executor.LOCAL, engine)),
        Trigger.CI,
        key,
        null,
        null,
        null);
  }

  @Test
  void blockedDuplicateReplaysTheRunThatCommittedFirst() throws Exception {
    String key = "race-" + UUID.randomUUID();
    CreateRunRequest request = request(key, Engine.CHROMIUM);

    Race race = race(request, request);

    RunCreationService.Result result = race.second().get(30, TimeUnit.SECONDS);
    assertThat(result.replay()).isTrue();
    assertThat(result.run().id()).isEqualTo(race.firstRunId());
    assertThat(RunTestSupport.count(jdbc, "runs")).isEqualTo(1);
  }

  @Test
  void blockedRequestWithADifferentBodyGetsAConflict() throws Exception {
    String key = "race-" + UUID.randomUUID();

    Race race = race(request(key, Engine.CHROMIUM), request(key, Engine.FIREFOX));

    assertThatThrownBy(() -> race.second().get(30, TimeUnit.SECONDS))
        .cause()
        .isInstanceOf(ApiProblemException.class)
        .satisfies(e ->
            assertThat(((ApiProblemException) e).type())
                .isEqualTo(ProblemType.IDEMPOTENCY_CONFLICT));
    assertThat(RunTestSupport.count(jdbc, "runs")).isEqualTo(1);
  }

  private record Race(UUID firstRunId, Future<RunCreationService.Result> second) {}

  private Race race(CreateRunRequest first, CreateRunRequest second) throws Exception {
    UUID suiteId = runs.findCurrentSuite(LocalProject.ID, "checkout-smoke").orElseThrow().id();
    CountDownLatch inserted = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    UUID[] firstId = new UUID[1];

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<?> holder =
        pool.submit(() ->
            tx.executeWithoutResult(status -> {
              firstId[0] =
                  runs.insertRunIfAbsent(
                          new RunRepository.NewRun(
                              LocalProject.ID,
                              suiteId,
                              first.trigger(),
                              null,
                              null,
                              first.idempotencyKey(),
                              RunRequestHash.of(first, mapper),
                              first.shardingOrDefault()))
                      .orElseThrow();
              inserted.countDown();
              await(commit);
            }));
    assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

    Future<RunCreationService.Result> secondResult =
        pool.submit(() -> service.create(LocalProject.ID, second));
    waitUntilAnInsertIsBlockedOnALock();
    commit.countDown();
    holder.get(10, TimeUnit.SECONDS);
    pool.shutdown();
    return new Race(firstId[0], secondResult);
  }

  private void waitUntilAnInsertIsBlockedOnALock() throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      long blocked =
          jdbc.sql(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                      + " AND query LIKE 'INSERT INTO runs%'")
              .query(Long.class)
              .single();
      if (blocked > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the second INSERT never blocked on the first transaction's row");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out holding the first transaction open");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
