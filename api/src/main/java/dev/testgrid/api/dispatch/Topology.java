package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.Engine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contracts §9 broker topology, declared by the API whenever it opens a connection (Spring's {@code
 * RabbitAdmin} redeclares after a broker restart). It is declared complete here, retry and
 * dead-letter arguments included, because RabbitMQ refuses to change a queue's arguments once it
 * exists ({@code PRECONDITION_FAILED}): a partial declaration now would break every dev broker when
 * the consumer arrives.
 */
@Configuration(proxyBeanMethods = false)
public class Topology {

  public static final String DISPATCH_EXCHANGE = "tg.dispatch";
  public static final String DEAD_LETTER_EXCHANGE = "tg.dlx";
  public static final String RETRY_QUEUE = "tg.targets.retry";
  public static final String DEAD_QUEUE = "tg.targets.dead";
  public static final String RUN_ID_HEADER = "x-tg-run-id";
  public static final String TRACEPARENT_HEADER = "traceparent";

  /**
   * Deliveries of one message before the broker dead-letters it (FR-5). Each one is a worker that
   * took the message and died or rejected it without an ack; a healthy claim acks on the first.
   */
  public static final int DELIVERY_LIMIT = 5;

  public static String queueName(Engine engine) {
    return "tg.targets." + engine.wireName();
  }

  public static String routingKey(Engine engine) {
    return "engine." + engine.wireName();
  }

  @Bean
  Declarables dispatchTopology() {
    List<Declarable> all = new ArrayList<>();
    DirectExchange dispatch = new DirectExchange(DISPATCH_EXCHANGE, true, false);
    FanoutExchange deadLetters = new FanoutExchange(DEAD_LETTER_EXCHANGE, true, false);
    all.add(dispatch);
    all.add(deadLetters);
    for (Engine engine : Engine.values()) {
      Queue queue =
          QueueBuilder.durable(queueName(engine))
              .quorum()
              .deliveryLimit(DELIVERY_LIMIT)
              .deadLetterExchange(DEAD_LETTER_EXCHANGE)
              .build();
      Binding binding = BindingBuilder.bind(queue).to(dispatch).with(routingKey(engine));
      all.add(queue);
      all.add(binding);
    }
    // Messages wait here for their per-message TTL, then go back through tg.dispatch.
    all.add(
        QueueBuilder.durable(RETRY_QUEUE).quorum().deadLetterExchange(DISPATCH_EXCHANGE).build());
    Queue dead = QueueBuilder.durable(DEAD_QUEUE).quorum().build();
    all.add(dead);
    all.add(BindingBuilder.bind(dead).to(deadLetters));
    return new Declarables(all);
  }
}
