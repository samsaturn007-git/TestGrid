package dev.testgrid.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PRD-05 FR-4 with LISTEN turned off: the 15 s ping (300 ms here) is also a sweep, so an event
 * whose NOTIFY was lost still arrives, one interval late. And the ping itself is a {@code :}
 * comment that EventSource ignores.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"testgrid.sse.listen=false", "testgrid.sse.ping-interval-ms=300"})
@Import(PostgresTestcontainer.class)
class RunEventStreamSweepTest {

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  private SseTestClient client;
  private UUID runId;

  @BeforeEach
  void setUp() {
    jdbc.sql(
            "TRUNCATE artifacts, attempt_heartbeats, case_results, attempts, failure_fingerprints, audit_events, run_events, run_targets, runs")
        .update();
    runId = RunEventStreamTest.insertRun(jdbc);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void pingsAreCommentsSentOnTheInterval() throws Exception {
    client = SseTestClient.connect(port, "/api/v1/runs/" + runId + "/events", null);
    assertThat(client.nextComment(2_000)).isEqualTo(":ping");
    assertThat(client.nextComment(2_000)).isEqualTo(":ping");
  }

  @Test
  void withoutNotifyAnEventStillArrivesOnTheNextSweep() throws Exception {
    client = SseTestClient.connect(port, "/api/v1/runs/" + runId + "/events", null);
    assertThat(client.next(2_000).event()).isEqualTo("RESYNC");
    long id = RunEventStreamTest.insertEvent(jdbc, runId, "TARGET_FINISHED", "{}");
    SseTestClient.Frame f = client.next(3_000);
    assertThat(f).isNotNull();
    assertThat(f.id()).isEqualTo(Long.toString(id));
    assertThat(f.event()).isEqualTo("TARGET_FINISHED");
  }
}
