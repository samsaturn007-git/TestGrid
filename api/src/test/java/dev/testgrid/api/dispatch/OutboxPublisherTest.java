package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.Engine;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PRD-04 FR-3 / T2: the outbox publisher against a real broker, with publisher confirms. */
class OutboxPublisherTest extends DispatchTestBase {

  @Autowired RabbitTemplate rabbit;

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void publishesConfirmedMessagesAndMarksTheirRows() {
    UUID chromiumRun = createRun(Engine.CHROMIUM);
    UUID firefoxRun = createRun(Engine.FIREFOX);
    dispatcher.dispatchOnce();

    var result = publisher.publishBatch();

    assertThat(result).isEqualTo(new OutboxPublisher.BatchResult(2, 2, 0));
    assertThat(
            count(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL"
                    + " AND publish_attempts = 1 AND last_error IS NULL"))
        .isEqualTo(2);
    assertMessage(receive("tg.targets.chromium"), chromiumRun);
    assertMessage(receive("tg.targets.firefox"), firefoxRun);
    // Published rows are not sent again.
    assertThat(publisher.publishBatch().locked()).isZero();
  }

  @Test
  void aCrashAfterPublishBeforeMarkSendsTheSameMessageAgain() {
    UUID runId = createRun(Engine.CHROMIUM);
    dispatcher.dispatchOnce();
    faults.arm(FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK);

    // The broker confirmed the message, then the "process died" before the mark committed.
    assertThatThrownBy(publisher::publishBatch).isInstanceOf(FaultInjector.InjectedFault.class);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"
                    + " AND publish_attempts = 0"))
        .isEqualTo(1);

    // The row is still due, so the next batch publishes it again and marks it this time.
    assertThat(publisher.publishBatch()).isEqualTo(new OutboxPublisher.BatchResult(1, 1, 0));
    Message first = receive("tg.targets.chromium");
    Message second = receive("tg.targets.chromium");
    assertMessage(first, runId);
    assertMessage(second, runId);
    // At-least-once: the duplicate is the same message (same ID and generation), so a claim
    // with it is either the first claim or a 409 (contracts §7/§9; consumer in T3).
    assertThat(second.getMessageProperties().getMessageId())
        .isEqualTo(first.getMessageProperties().getMessageId());
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(rabbit.receive("tg.targets.chromium", 200)).isNull();
  }

  @Test
  void anUnroutableMessageStaysUnpublishedAndBacksOff() {
    admin.deleteQueue("tg.targets.webkit");
    try {
      createRun(Engine.WEBKIT);
      dispatcher.dispatchOnce();

      // `mandatory` makes the broker return the message instead of dropping it silently.
      assertThat(publisher.publishBatch()).isEqualTo(new OutboxPublisher.BatchResult(1, 0, 1));
      Map<String, Object> row =
          jdbc.sql(
                  "SELECT published_at, publish_attempts, last_error,"
                      + " next_attempt_at > now() AS backing_off FROM outbox_events")
              .query()
              .singleRow();
      assertThat(row.get("published_at")).isNull();
      assertThat(row).containsEntry("publish_attempts", 1).containsEntry("backing_off", true);
      assertThat((String) row.get("last_error")).startsWith("unroutable: NO_ROUTE");
      // Not due yet, so the next batch leaves it alone.
      assertThat(publisher.publishBatch().locked()).isZero();

      // Once the queue is back and the backoff has passed, it goes through.
      admin.initialize();
      jdbc.sql("UPDATE outbox_events SET next_attempt_at = now()").update();
      assertThat(publisher.publishBatch()).isEqualTo(new OutboxPublisher.BatchResult(1, 1, 0));
      assertThat(count("SELECT publish_attempts FROM outbox_events")).isEqualTo(2);
      assertThat(receive("tg.targets.webkit")).isNotNull();
    } finally {
      admin.initialize();
    }
  }

  @Test
  void theDeclaredQueuesExistAsQuorumQueues() {
    // A smoke check only; T3 verifies every argument of the contracts §9 topology.
    for (String queue : QUEUES) {
      assertThat(admin.getQueueInfo(queue)).as(queue).isNotNull();
      // Redeclaring as a classic queue is refused because x-queue-type differs.
      assertThatThrownBy(() ->
              rabbit.execute(channel -> channel.queueDeclare(queue, true, false, false, Map.of())))
          .as(queue)
          .hasStackTraceContaining("PRECONDITION_FAILED");
    }
  }

  private Message receive(String queue) {
    Message message = rabbit.receive(queue, 5000);
    assertThat(message).as("a message on " + queue).isNotNull();
    return message;
  }

  private void assertMessage(Message message, UUID runId) {
    UUID targetId = targetOf(runId);
    String stored =
        jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = :t")
            .param("t", targetId)
            .query(String.class)
            .single();
    String traceparent =
        jdbc.sql("SELECT traceparent FROM outbox_events WHERE aggregate_id = :t")
            .param("t", targetId)
            .query(String.class)
            .single();
    JsonNode body = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    assertThat(body).isEqualTo(mapper.readTree(stored));
    assertThat(body.get("targetId").asString()).isEqualTo(targetId.toString());
    var props = message.getMessageProperties();
    assertThat(props.getMessageId()).isEqualTo(body.get("messageId").asString());
    assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    assertThat(props.getContentType()).isEqualTo("application/json");
    assertThat(props.<String>getHeader(Topology.TRACEPARENT_HEADER)).isEqualTo(traceparent);
    assertThat(props.<String>getHeader(Topology.RUN_ID_HEADER)).isEqualTo(runId.toString());
  }
}
