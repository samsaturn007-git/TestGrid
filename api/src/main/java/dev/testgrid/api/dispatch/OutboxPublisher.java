package dev.testgrid.api.dispatch;

import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.common.Redactor;
import dev.testgrid.api.common.Traceparent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-04 FR-3: moves committed outbox rows to the broker. One batch is one transaction: lock the
 * due rows ({@code FOR UPDATE SKIP LOCKED}), publish each with a publisher confirm and the {@code
 * mandatory} flag, then mark the confirmed ones published. A nack, an unroutable return, a timeout
 * or a broken connection leaves the row unpublished with {@code publish_attempts + 1} and a backoff
 * in {@code next_attempt_at}.
 *
 * <p>Delivery is at least once. If the process dies after the broker confirmed but before the mark
 * commits, the row is sent again: the duplicate carries the same {@code messageId} and {@code
 * dispatchGeneration}, and the claim protocol makes it harmless (contracts §7, Q-002/Q-003).
 */
@Service
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxRepository outbox;
  private final RabbitTemplate rabbit;
  private final DispatchProperties properties;
  private final FaultInjector faults;
  private final JsonMapper mapper;

  public OutboxPublisher(
      OutboxRepository outbox,
      RabbitTemplate rabbit,
      DispatchProperties properties,
      FaultInjector faults,
      JsonMapper mapper) {
    this.outbox = outbox;
    this.rabbit = rabbit;
    this.properties = properties;
    this.faults = faults;
    this.mapper = mapper;
  }

  /** What one batch did; {@code locked} equal to the batch size means more may be waiting. */
  public record BatchResult(int locked, int published, int failed) {}

  private record InFlight(OutboxRepository.Pending row, CorrelationData correlation) {}

  @Transactional
  public BatchResult publishBatch() {
    List<OutboxRepository.Pending> rows = outbox.lockBatch(properties.outboxBatchSize());
    if (rows.isEmpty()) {
      return new BatchResult(0, 0, 0);
    }
    List<InFlight> inFlight = new ArrayList<>(rows.size());
    Map<OutboxRepository.Pending, String> failures = new LinkedHashMap<>();
    for (OutboxRepository.Pending row : rows) {
      try {
        CorrelationData correlation = new CorrelationData(Long.toString(row.id()));
        rabbit.send(
            Topology.DISPATCH_EXCHANGE,
            Topology.routingKey(row.engine()),
            message(row),
            correlation);
        inFlight.add(new InFlight(row, correlation));
      } catch (AmqpException e) {
        failures.put(row, "publish failed: " + e.getMessage());
      }
    }

    List<Long> confirmed = new ArrayList<>(inFlight.size());
    long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.confirmTimeoutMs());
    for (InFlight sent : inFlight) {
      String error = awaitConfirm(sent.correlation(), deadline);
      if (error == null) {
        confirmed.add(sent.row().id());
      } else {
        failures.put(sent.row(), error);
      }
    }

    // A crash here leaves confirmed messages on the broker and their rows unpublished (Q-003).
    faults.trip(FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK);

    outbox.markPublished(confirmed);
    failures.forEach((row, error) -> {
      long delayMs = properties.publishBackoffMs(row.publishAttempts() + 1);
      outbox.markFailed(row.id(), Redactor.redact(error), delayMs);
      try (var ctx = context(row)) {
        log.warn(
            "outbox publish failed outboxId={} publishAttempts={} retryInMs={} error={}",
            row.id(),
            row.publishAttempts() + 1,
            delayMs,
            Redactor.redact(error));
      }
    });
    if (!confirmed.isEmpty()) {
      log.info("published {} outbox row(s), {} failed", confirmed.size(), failures.size());
    }
    return new BatchResult(rows.size(), confirmed.size(), failures.size());
  }

  /** {@code null} when the broker took responsibility for the message, else the reason. */
  private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
    long remaining = Math.max(0, deadlineNanos - System.nanoTime());
    CorrelationData.Confirm confirm;
    try {
      confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      return "no publisher confirm within the timeout";
    } catch (ExecutionException e) {
      return "publisher confirm failed: " + e.getCause();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted while waiting for the publisher confirm";
    }
    if (!confirm.ack()) {
      return "broker nack: " + confirm.reason();
    }
    // With `mandatory`, an unroutable message is returned (before the ack). An ack alone would
    // only mean the broker dropped it on purpose, so a return counts as a failure.
    if (correlation.getReturned() != null) {
      return "unroutable: " + correlation.getReturned().getReplyText();
    }
    return null;
  }

  private Message message(OutboxRepository.Pending row) {
    JsonNode body = mapper.readTree(row.payload());
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setContentEncoding(StandardCharsets.UTF_8.name());
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(body.get("messageId").asString());
    props.setType("TARGET_DISPATCHED");
    props.setHeader(Topology.RUN_ID_HEADER, row.runId().toString());
    if (row.traceparent() != null) {
      props.setHeader(Topology.TRACEPARENT_HEADER, row.traceparent());
    }
    return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
  }

  private static LogContext context(OutboxRepository.Pending row) {
    return LogContext.open()
        .put("runId", row.runId())
        .put("targetId", row.targetId())
        .put("traceId", row.traceparent() == null ? null : Traceparent.traceId(row.traceparent()));
  }
}
