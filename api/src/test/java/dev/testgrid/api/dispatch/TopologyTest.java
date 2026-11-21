package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-04 FR-4: the topology the API declares is exactly contracts §9, read back from the broker's
 * management API, and the routes it promises work on a real broker: the retry queue's TTL hands a
 * message back to its engine queue, and the delivery limit and a reject both dead-letter.
 */
class TopologyTest extends DispatchTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final byte[] BODY =
      "{\"messageId\":\"m\",\"targetId\":\"t\",\"dispatchGeneration\":1,\"schemaVersion\":1}"
          .getBytes(StandardCharsets.UTF_8);

  @Autowired RabbitMQContainer container;

  private final HttpClient http = HttpClient.newHttpClient();
  private Connection connection;
  private Channel channel;

  @BeforeEach
  void connect() throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(container.getHost());
    factory.setPort(container.getAmqpPort());
    factory.setUsername(container.getAdminUsername());
    factory.setPassword(container.getAdminPassword());
    connection = factory.newConnection("topology-test");
    channel = connection.createChannel();
  }

  @AfterEach
  void disconnect() throws Exception {
    connection.close();
  }

  @Test
  void theEngineQueuesHaveExactlyTheContractArguments() {
    for (String engine : List.of("chromium", "firefox", "webkit")) {
      JsonNode queue = management("/api/queues/%2F/tg.targets." + engine);
      assertThat(queue.get("type").asString()).isEqualTo("quorum");
      assertThat(queue.get("durable").asBoolean()).isTrue();
      assertThat(queue.get("auto_delete").asBoolean()).isFalse();
      assertThat(arguments(queue))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "x-queue-type", "quorum",
                  "x-delivery-limit", "5",
                  "x-dead-letter-exchange", "tg.dlx"));
    }
  }

  @Test
  void theRetryAndDeadQueuesHaveExactlyTheContractArguments() {
    JsonNode retry = management("/api/queues/%2F/tg.targets.retry");
    assertThat(retry.get("type").asString()).isEqualTo("quorum");
    assertThat(arguments(retry))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("x-queue-type", "quorum", "x-dead-letter-exchange", "tg.dispatch"));
    JsonNode dead = management("/api/queues/%2F/tg.targets.dead");
    assertThat(dead.get("type").asString()).isEqualTo("quorum");
    assertThat(arguments(dead)).containsExactlyEntriesOf(Map.of("x-queue-type", "quorum"));
  }

  @Test
  void theExchangesAndBindingsMatchTheContract() {
    JsonNode dispatch = management("/api/exchanges/%2F/tg.dispatch");
    assertThat(dispatch.get("type").asString()).isEqualTo("direct");
    assertThat(dispatch.get("durable").asBoolean()).isTrue();
    JsonNode dlx = management("/api/exchanges/%2F/tg.dlx");
    assertThat(dlx.get("type").asString()).isEqualTo("fanout");
    assertThat(dlx.get("durable").asBoolean()).isTrue();

    for (String engine : List.of("chromium", "firefox", "webkit")) {
      JsonNode bindings = management("/api/bindings/%2F/e/tg.dispatch/q/tg.targets." + engine);
      assertThat(bindings.size()).as(engine).isEqualTo(1);
      assertThat(bindings.get(0).get("routing_key").asString()).isEqualTo("engine." + engine);
    }
    assertThat(management("/api/bindings/%2F/e/tg.dlx/q/tg.targets.dead").size()).isEqualTo(1);
    // Nothing else is bound to the dispatch exchange: the retry queue is reached by name only.
    assertThat(management("/api/exchanges/%2F/tg.dispatch/bindings/source").size()).isEqualTo(3);
  }

  /**
   * The worker's retry publish (contracts §9): default exchange, routing key {@code
   * tg.targets.retry}, a per-message TTL and {@code CC: engine.<engine>}. When the TTL expires the
   * message is dead-lettered to {@code tg.dispatch} with its routing keys, and the CC key routes it
   * back to its engine queue.
   */
  @Test
  void anExpiredRetryMessageReturnsToItsEngineQueue() throws Exception {
    var props =
        new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2)
            .messageId("m-retry")
            .expiration("200")
            .headers(Map.of("CC", List.of("engine.firefox"), "x-tg-retries", 1))
            .build();
    channel.basicPublish("", Topology.RETRY_QUEUE, true, props, BODY);

    GetResponse back = poll("tg.targets.firefox", 10_000);
    assertThat(back).as("message back in tg.targets.firefox").isNotNull();
    assertThat(back.getBody()).isEqualTo(BODY);
    assertThat(back.getProps().getMessageId()).isEqualTo("m-retry");
    assertThat(back.getProps().getHeaders().get("x-tg-retries")).isEqualTo(1);
    assertThat(String.valueOf(back.getProps().getHeaders().get("x-first-death-reason")))
        .isEqualTo("expired");
    assertThat(channel.basicGet("tg.targets.chromium", true)).isNull();
    assertThat(channel.basicGet(Topology.DEAD_QUEUE, true)).isNull();
    channel.basicAck(back.getEnvelope().getDeliveryTag(), false);
  }

  /**
   * A message that keeps coming back because its consumer dies before acking (a worker crash: the
   * channel closes with the delivery unacked) ends in the dead-letter queue after the limit.
   * Checked on RabbitMQ 4.4.1: a {@code basic.nack} with requeue does not count toward the limit
   * (an earlier version of this test looped forever), so the worker never relies on requeue.
   */
  @Test
  void theDeliveryLimitDeadLettersAMessageWhoseConsumerKeepsDying() throws Exception {
    channel.basicPublish(
        Topology.DISPATCH_EXCHANGE,
        "engine.webkit",
        true,
        new AMQP.BasicProperties.Builder().deliveryMode(2).messageId("m-limit").build(),
        BODY);

    int deliveries = 0;
    while (true) {
      Channel worker = connection.createChannel();
      GetResponse delivery = poll(worker, "tg.targets.webkit", 2_000);
      if (delivery == null) {
        worker.close();
        break;
      }
      deliveries++;
      assertThat(deliveries).as("bounded redeliveries").isLessThanOrEqualTo(10);
      // Crash: the channel goes away with the message unacked.
      worker.close();
    }
    GetResponse dead = poll(channel, Topology.DEAD_QUEUE, 5_000);
    assertThat(dead).as("message in tg.targets.dead").isNotNull();
    assertThat(dead.getProps().getMessageId()).isEqualTo("m-limit");
    assertThat(String.valueOf(dead.getProps().getHeaders().get("x-first-death-reason")))
        .isEqualTo("delivery_limit");
    // x-delivery-limit 5: the first delivery plus five redeliveries, then dead-lettered.
    assertThat(deliveries).isEqualTo(Topology.DELIVERY_LIMIT + 1);
  }

  /** A requeue is not a crash: it does not move a message toward the dead-letter queue. */
  @Test
  void aNackWithRequeueDoesNotCountTowardTheLimit() throws Exception {
    channel.basicPublish(
        Topology.DISPATCH_EXCHANGE,
        "engine.webkit",
        true,
        new AMQP.BasicProperties.Builder().deliveryMode(2).messageId("m-requeue").build(),
        BODY);
    for (int i = 0; i < Topology.DELIVERY_LIMIT + 3; i++) {
      GetResponse delivery = poll(channel, "tg.targets.webkit", 2_000);
      assertThat(delivery).as("delivery " + (i + 1)).isNotNull();
      channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
    }
    assertThat(channel.basicGet(Topology.DEAD_QUEUE, true)).isNull();
    GetResponse still = poll(channel, "tg.targets.webkit", 2_000);
    assertThat(still).isNotNull();
    channel.basicAck(still.getEnvelope().getDeliveryTag(), false);
  }

  /** The worker rejects a message it cannot parse without requeue: straight to dead letters. */
  @Test
  void aRejectedMessageIsDeadLetteredAtOnce() throws Exception {
    channel.basicPublish(
        Topology.DISPATCH_EXCHANGE,
        "engine.chromium",
        true,
        new AMQP.BasicProperties.Builder().deliveryMode(2).messageId("m-poison").build(),
        "not json".getBytes(StandardCharsets.UTF_8));
    GetResponse delivery = poll("tg.targets.chromium", 5_000);
    assertThat(delivery).isNotNull();
    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);

    GetResponse dead = poll(Topology.DEAD_QUEUE, 5_000);
    assertThat(dead).isNotNull();
    assertThat(dead.getProps().getMessageId()).isEqualTo("m-poison");
    assertThat(String.valueOf(dead.getProps().getHeaders().get("x-first-death-reason")))
        .isEqualTo("rejected");
  }

  private GetResponse poll(String queue, long timeoutMs) throws Exception {
    return poll(channel, queue, timeoutMs);
  }

  private static GetResponse poll(Channel channel, String queue, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    do {
      GetResponse response = channel.basicGet(queue, false);
      if (response != null) {
        return response;
      }
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);
    return null;
  }

  private static Map<String, String> arguments(JsonNode queue) {
    var out = new java.util.TreeMap<String, String>();
    JsonNode args = queue.get("arguments");
    for (String name : args.propertyNames()) {
      out.put(name, args.get(name).asString());
    }
    return out;
  }

  private JsonNode management(String path) {
    String auth =
        Base64.getEncoder()
            .encodeToString(
                (container.getAdminUsername() + ":" + container.getAdminPassword())
                    .getBytes(StandardCharsets.UTF_8));
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(container.getHttpUrl() + path))
                  .header("Authorization", "Basic " + auth)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
      return JSON.readTree(response.body());
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
