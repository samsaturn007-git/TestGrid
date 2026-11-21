package dev.testgrid.api.crash;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.dispatch.Topology;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AC-2 (Q-002, invariants I1 and I2): the same dispatch message is delivered three times, to three
 * slots on two workers at once. One claim wins, the other two get 409 and are acked; one attempt,
 * one terminal result.
 */
class DuplicateMessageTest extends CrashTestBase {

  @Autowired RabbitTemplate template;

  @RepeatedTest(5)
  void theSameMessageThreeTimesGivesOneLeaseAndOneResult() throws Exception {
    UUID target = onlyTarget(createRun("chromium"));
    await(
        Duration.ofSeconds(10),
        () -> count("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL") == 1,
        "the dispatch is published");
    String payload =
        jdbc.sql("SELECT payload::text FROM outbox_events").query(String.class).single();
    for (int i = 0; i < 2; i++) {
      MessageProperties props = new MessageProperties();
      props.setContentType("application/json");
      template.send(
          Topology.DISPATCH_EXCHANGE,
          "engine.chromium",
          MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
              .andProperties(props)
              .build());
    }
    await(Duration.ofSeconds(5), () -> depth("tg.targets.chromium") == 3, "three copies queued");

    SimWorker a = worker("w-a", 2, 300, ALL_ENGINES).start();
    SimWorker b = worker("w-b", 1, 300, ALL_ENGINES).start();
    await(Duration.ofSeconds(15), () -> terminal(target), "target terminal");
    await(
        Duration.ofSeconds(5),
        () ->
            a.summary().getOrDefault("claim 409", 0) + b.summary().getOrDefault("claim 409", 0)
                == 2,
        "both duplicates refused");

    assertThat(targetStatus(target)).isEqualTo("PASSED");
    assertThat(attemptsOf(target)).containsExactly("1:PASSED:-");
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_CLAIMED'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM run_events WHERE type = 'TARGET_FINISHED'"))
        .isEqualTo(1);
    await(Duration.ofSeconds(5), () -> depth("tg.targets.chromium") == 0, "queue drained");
    EvidenceRecorder.note("w-a " + a.summary() + " w-b " + b.summary());
  }
}
