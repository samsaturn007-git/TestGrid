package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * FR-8: API log lines are JSON; lines about an attempt carry runId, targetId, attemptId, workerId
 * and traceId; neither the service token nor a lease token is ever written, including on the
 * rejection paths (wrong token, stale lease, malformed completion).
 */
@ExtendWith(OutputCaptureExtension.class)
class LogCorrelationTest extends WorkerApiTestBase {

  @Test
  void attemptLogLinesAreCorrelatedJsonAndNeverContainATokenOrLeaseToken(CapturedOutput output) {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    lease(claim, "heartbeat");
    complete(claim, "k-bad", "COMPLETED_WITH_RESULTS", "[]");
    complete(claim, "k-ok", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    complete(claim, "k-other", "COMPLETED_WITH_RESULTS", checkoutResults(Map.of()));
    post(
        internalPort(),
        "/internal/v1/targets/" + claim.targetId() + "/claim",
        "{}",
        "wrong-" + TOKEN);
    lease(
        new Claim(claim.attemptId(), AttemptService.newLeaseToken(), null, null, ""), "heartbeat");

    String all = output.getAll();
    assertThat(all).doesNotContain(TOKEN).doesNotContain(claim.token());

    List<JsonNode> attemptLines = new ArrayList<>();
    for (String line : all.split("\\R")) {
      if (line.startsWith("{") && line.contains(claim.attemptId())) {
        attemptLines.add(mapper.readTree(line));
      }
    }
    assertThat(attemptLines).hasSizeGreaterThanOrEqualTo(4);
    for (JsonNode line : attemptLines) {
      for (String key : List.of("runId", "targetId", "attemptId", "workerId", "traceId")) {
        assertThat(line.path(key).asString()).as(key + " in " + line).isNotBlank();
      }
    }
    assertThat(attemptLines)
        .extracting(l -> l.path("message").asString())
        .anyMatch(m -> m.startsWith("claimed target"))
        .anyMatch(m -> m.startsWith("attempt completed"))
        .anyMatch(m -> m.startsWith("rejected late or invalid complete"));
  }
}
