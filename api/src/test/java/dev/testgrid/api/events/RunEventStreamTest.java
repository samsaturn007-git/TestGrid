package dev.testgrid.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PRD-05 FR-4 and the T5 verification ("an SSE replay integration test in the API"): the real
 * stream over HTTP against real PostgreSQL. The ping interval is 60 s here, so an event that
 * arrives within seconds came through LISTEN/NOTIFY, not the ping sweep ({@link
 * RunEventStreamSweepTest} covers that).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "testgrid.sse.ping-interval-ms=60000",
      "testgrid.sse.max-streams=3",
      "testgrid.sse.replay-limit=5"
    })
@Import(PostgresTestcontainer.class)
class RunEventStreamTest {

  /** Well under the 60 s ping: only NOTIFY can deliver this fast. */
  static final long NOTIFY_MS = 3_000;

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private RunEventStreams streams;
  private final List<SseTestClient> clients = new ArrayList<>();
  private final List<UUID> runs = new ArrayList<>();
  private UUID runId;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE case_results, attempts, run_events, run_targets, runs").update();
    runId = insertRun();
  }

  @AfterEach
  void tearDown() throws Exception {
    for (SseTestClient c : clients) {
      c.close();
    }
    // A server notices a closed client only when it next writes to it, and the ping here is
    // 60 s. A new event on each run makes that write, so every slot is free for the next test.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> {
          for (UUID run : runs) {
            insertEvent(jdbc, run, "CASE_FINISHED", "{}");
          }
          return streams.openStreams() == 0;
        });
  }

  static UUID insertRun(JdbcClient jdbc) {
    return jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, idempotency_key, request_hash)
            SELECT project_id, id, 'RUNNING', 'MANUAL', :k, decode(repeat('00', 32), 'hex')
            FROM suites WHERE project_id = :p AND suite_key = 'checkout-smoke' AND is_current
            RETURNING id
            """)
        .param("k", "sse-" + UUID.randomUUID())
        .param("p", LocalProject.ID)
        .query(UUID.class)
        .single();
  }

  private UUID insertRun() {
    UUID id = insertRun(jdbc);
    runs.add(id);
    return id;
  }

  static long insertEvent(JdbcClient jdbc, UUID runId, String type, String payload) {
    return jdbc.sql(
            "INSERT INTO run_events (run_id, type, payload) VALUES (:r, :t, CAST(:p AS jsonb))"
                + " RETURNING id")
        .param("r", runId)
        .param("t", type)
        .param("p", payload)
        .query(Long.class)
        .single();
  }

  private long event(String type) {
    return insertEvent(jdbc, runId, type, "{\"n\":1}");
  }

  /** A stream that must open: a 429 or 404 here would otherwise read as "no events". */
  private SseTestClient connect(UUID run, String lastEventId) throws Exception {
    SseTestClient c = raw(run, lastEventId);
    assertThat(c.status).as("stream opened").isEqualTo(200);
    return c;
  }

  private SseTestClient raw(UUID run, String lastEventId) throws Exception {
    SseTestClient c = SseTestClient.connect(port, "/api/v1/runs/" + run + "/events", lastEventId);
    clients.add(c);
    return c;
  }

  @Test
  void aFirstConnectionStartsWithOneResyncCarryingTheNewestEventId() throws Exception {
    event("RUN_CREATED");
    long latest = event("TARGET_DISPATCHED");

    SseTestClient c = connect(runId, null);
    assertThat(c.status).isEqualTo(200);
    assertThat(c.response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/event-stream");
    assertThat(c.response.headers().firstValue("X-Accel-Buffering")).hasValue("no");

    SseTestClient.Frame first = c.next(NOTIFY_MS);
    assertThat(first.event()).isEqualTo("RESYNC");
    assertThat(first.id()).isEqualTo(Long.toString(latest));
    assertThat((Integer) JsonPath.read(first.data(), "$.eventId")).isEqualTo((int) latest);
    // History is not replayed to a client that has no Last-Event-ID: it loads the snapshot.
    assertThat(c.drain(500)).isEmpty();
  }

  @Test
  void aNewEventArrivesThroughNotifyWithTheContractEnvelope() throws Exception {
    SseTestClient c = connect(runId, null);
    assertThat(c.next(NOTIFY_MS).event()).isEqualTo("RESYNC");

    long id = insertEvent(jdbc, runId, "TARGET_CLAIMED", "{\"workerId\":\"w-1\",\"attemptNo\":2}");
    SseTestClient.Frame f = c.next(NOTIFY_MS);
    assertThat(f).as("delivered by NOTIFY well before the 60 s ping").isNotNull();
    assertThat(f.id()).isEqualTo(Long.toString(id));
    assertThat(f.event()).isEqualTo("TARGET_CLAIMED");
    assertThat((Integer) JsonPath.read(f.data(), "$.eventId")).isEqualTo((int) id);
    assertThat((Integer) JsonPath.read(f.data(), "$.schemaVersion")).isEqualTo(1);
    assertThat((String) JsonPath.read(f.data(), "$.type")).isEqualTo("TARGET_CLAIMED");
    assertThat((String) JsonPath.read(f.data(), "$.runId")).isEqualTo(runId.toString());
    assertThat((Object) JsonPath.read(f.data(), "$.targetId")).isNull();
    assertThat((String) JsonPath.read(f.data(), "$.occurredAt")).isNotBlank();
    assertThat((String) JsonPath.read(f.data(), "$.data.workerId")).isEqualTo("w-1");
    assertThat((Integer) JsonPath.read(f.data(), "$.data.attemptNo")).isEqualTo(2);
  }

  @Test
  void eventsArriveOnceInIdOrderAndARolledBackEventNeverArrives() throws Exception {
    SseTestClient c = connect(runId, null);
    c.next(NOTIFY_MS);

    long[] committed = new long[3];
    tx.executeWithoutResult(s -> {
      committed[0] = event("TARGET_CLAIMED");
      committed[1] = event("TARGET_STARTED");
    });
    tx.executeWithoutResult(s -> {
      event("TARGET_FINISHED");
      s.setRollbackOnly();
    });
    committed[2] = event("RUN_FINISHED");

    List<SseTestClient.Frame> got = c.drain(NOTIFY_MS);
    assertThat(got.stream().map(SseTestClient.Frame::id).toList())
        .containsExactly(
            Long.toString(committed[0]), Long.toString(committed[1]), Long.toString(committed[2]));
    assertThat(got.stream().map(SseTestClient.Frame::event).toList())
        .containsExactly("TARGET_CLAIMED", "TARGET_STARTED", "RUN_FINISHED");
  }

  @Test
  void reconnectingWithLastEventIdReplaysOnlyWhatCameAfterIt() throws Exception {
    long a = event("RUN_CREATED");
    long b = event("TARGET_DISPATCHED");
    long c = event("TARGET_CLAIMED");

    SseTestClient client = connect(runId, Long.toString(a));
    List<SseTestClient.Frame> replay = client.drain(1_000);
    assertThat(replay.stream().map(SseTestClient.Frame::id).toList())
        .containsExactly(Long.toString(b), Long.toString(c));

    long d = event("TARGET_STARTED");
    assertThat(client.next(NOTIFY_MS).id()).isEqualTo(Long.toString(d));
  }

  @Test
  void aClientTooFarBehindGetsOneResyncInsteadOfAFlood() throws Exception {
    long first = event("RUN_CREATED");
    long latest = 0;
    for (int i = 0; i < 6; i++) { // replay-limit is 5
      latest = event("CASE_FINISHED");
    }
    SseTestClient c = connect(runId, Long.toString(first));
    List<SseTestClient.Frame> got = c.drain(1_000);
    assertThat(got).hasSize(1);
    assertThat(got.get(0).event()).isEqualTo("RESYNC");
    assertThat(got.get(0).id()).isEqualTo(Long.toString(latest));

    long next = event("RUN_FINISHED");
    assertThat(c.next(NOTIFY_MS).id()).isEqualTo(Long.toString(next));
  }

  @Test
  void aStreamCarriesOnlyItsOwnRunsEvents() throws Exception {
    UUID other = insertRun();
    SseTestClient c = connect(runId, null);
    c.next(NOTIFY_MS);
    insertEvent(jdbc, other, "RUN_CREATED", "{}");
    long mine = event("RUN_CREATED");
    List<SseTestClient.Frame> got = c.drain(NOTIFY_MS);
    assertThat(got.stream().map(SseTestClient.Frame::id).toList())
        .containsExactly(Long.toString(mine));
  }

  @Test
  void anUnknownRunIsAProblemNotAStream() throws Exception {
    SseTestClient c = raw(UUID.randomUUID(), null);
    assertThat(c.status).isEqualTo(404);
    assertThat(c.body()).contains("https://testgrid.local/problems/not-found");
  }

  @Test
  void aMalformedLastEventIdIsRejected() throws Exception {
    for (String bad : new String[] {"abc", "-1", "1.5"}) {
      SseTestClient c = raw(runId, bad);
      assertThat(c.status).as(bad).isEqualTo(400);
      assertThat(c.body()).contains("validation-failed").contains("Last-Event-ID");
    }
  }

  @Test
  void openStreamsAreCappedAndASlotFreesWhenAClientLeaves() throws Exception {
    List<SseTestClient> open = new ArrayList<>();
    for (int i = 0; i < 3; i++) { // max-streams is 3
      SseTestClient c = connect(runId, null);
      assertThat(c.status).isEqualTo(200);
      c.next(NOTIFY_MS);
      open.add(c);
    }
    SseTestClient refused = raw(runId, null);
    assertThat(refused.status).isEqualTo(429);
    assertThat(refused.response.headers().firstValue("Retry-After")).hasValue("5");
    assertThat(refused.body()).contains("https://testgrid.local/problems/rate-limited");

    open.get(0).close();
    // The server notices a closed client on its next write; a new event makes one.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> {
          event("CASE_FINISHED");
          return streams.openStreams() < 3;
        });
    SseTestClient again = connect(runId, null);
    assertThat(again.status).isEqualTo(200);
  }
}
