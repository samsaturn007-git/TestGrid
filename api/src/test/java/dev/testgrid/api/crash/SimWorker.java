package dev.testgrid.api.crash;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.internal.AttemptService;
import dev.testgrid.api.internal.WorkerApi;
import dev.testgrid.api.problem.ApiProblemException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A simulated worker for the PRD-04 crash tests: a real AMQP consumer (manual acks, prefetch =
 * slots) that claims, starts, heartbeats and completes through the real {@link AttemptService} and
 * settles each message as contracts §9 says. It is <b>not</b> the Node worker: there is no
 * Playwright, an "attempt" sleeps for {@code runMs} and reports every case PASSED. The Node
 * worker's own decision table is unit-tested in worker/test and exercised live by the drills.
 *
 * <p>A {@link Fault} makes it crash at one point, {@code times} times: the channel closes with the
 * delivery unacked (what a killed process does), heartbeats for that attempt stop, and the worker
 * "restarts" with a new channel.
 */
final class SimWorker implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SimWorker.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  enum Fault {
    /** Dies as soon as a message arrives, before claiming it (poison for this worker). */
    ON_RECEIVE,
    /** Claims and starts, then dies: the attempt keeps a lease nobody renews (Q-004). */
    AFTER_CLAIM,
    /** Completes, then dies before the ack: the broker redelivers a finished target (Q-008). */
    AFTER_COMPLETE_BEFORE_ACK
  }

  /** What happened to each delivery, for the assertions and the evidence file. */
  final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

  final AtomicInteger crashes = new AtomicInteger();

  private final String name;
  private final AttemptService attempts;
  private final ConnectionFactory factory;
  private final int slots;
  private final long runMs;
  private final List<String> queues;
  private final ExecutorService pool;
  private final Semaphore free;
  private volatile Fault fault;
  private final AtomicInteger faultsLeft = new AtomicInteger();
  private volatile Connection connection;
  private volatile Channel channel;
  private volatile boolean closed;

  SimWorker(
      String name,
      AttemptService attempts,
      String host,
      int port,
      String user,
      String password,
      int slots,
      long runMs,
      List<String> engines) {
    this.name = name;
    this.attempts = attempts;
    this.slots = slots;
    this.runMs = runMs;
    this.queues = engines.stream().map(e -> "tg.targets." + e).toList();
    this.pool = Executors.newCachedThreadPool(Thread.ofVirtual().name(name + "-", 0).factory());
    this.free = new Semaphore(slots);
    this.factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    factory.setUsername(user);
    factory.setPassword(password);
    // A broker restart (AC-5): the client reconnects and re-subscribes by itself; acks for
    // deliveries from before the restart are dropped, so the broker redelivers those.
    factory.setAutomaticRecoveryEnabled(true);
    factory.setNetworkRecoveryInterval(500);
    factory.setRequestedHeartbeat(5);
  }

  SimWorker withFault(Fault fault, int times) {
    this.fault = fault;
    this.faultsLeft.set(times);
    return this;
  }

  SimWorker start() throws Exception {
    connection = factory.newConnection(name);
    subscribe();
    return this;
  }

  private synchronized void subscribe() throws IOException {
    Channel ch = connection.createChannel();
    ch.basicQos(slots);
    for (String queue : queues) {
      ch.basicConsume(
          queue,
          false,
          new DefaultConsumer(ch) {
            @Override
            public void handleDelivery(
                String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) {
              pool.submit(() -> deliver(ch, envelope, body));
            }
          });
    }
    channel = ch;
  }

  /** The process died: unacked deliveries go back to the queue; heartbeats stop. */
  private synchronized void crash(Channel ch, String where) {
    crashes.incrementAndGet();
    events.add("crash " + where);
    if (ch != channel) {
      return;
    }
    try {
      ch.abort();
      if (!closed) {
        subscribe();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean fire(Fault point) {
    return fault == point && faultsLeft.getAndUpdate(n -> Math.max(n - 1, 0)) > 0;
  }

  private void deliver(Channel ch, Envelope envelope, byte[] body) {
    try {
      free.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    try {
      if (fire(Fault.ON_RECEIVE)) {
        crash(ch, "on receive");
        return;
      }
      JsonNode message = JSON.readTree(body);
      UUID targetId = UUID.fromString(message.get("targetId").asString());
      var request =
          new WorkerApi.ClaimRequest(
              name,
              UUID.fromString(message.get("messageId").asString()),
              message.get("dispatchGeneration").asInt());
      WorkerApi.ClaimResponse claim;
      try {
        claim = attempts.claim(targetId, request);
      } catch (ApiProblemException e) {
        int status = e.type().status().value();
        events.add("claim " + status + " gen " + request.dispatchGeneration());
        if (status == 409 || status == 404) {
          ack(ch, envelope);
        } else {
          // 429 and friends would go to the retry queue; the crash tests never hit them.
          ch.basicNack(envelope.getDeliveryTag(), false, true);
        }
        return;
      }
      events.add("claimed gen " + request.dispatchGeneration() + " attempt " + claim.attemptNo());
      attempts.start(claim.attemptId(), claim.leaseToken());
      if (fire(Fault.AFTER_CLAIM)) {
        crash(ch, "after claim");
        return;
      }
      String outcome = run(claim);
      if (outcome == null) {
        return;
      }
      if (fire(Fault.AFTER_COMPLETE_BEFORE_ACK)) {
        crash(ch, "after complete before ack");
        return;
      }
      ack(ch, envelope);
      events.add("completed " + outcome);
    } catch (Exception e) {
      LOG.warn("{} delivery failed: {}", name, e.toString());
      events.add("error " + e);
    } finally {
      free.release();
    }
  }

  /** Sleeps runMs with a heartbeat every second; completes PASSED, or CANCELLED on request. */
  private String run(WorkerApi.ClaimResponse claim) throws InterruptedException {
    long end = System.currentTimeMillis() + runMs;
    boolean cancelled = false;
    while (System.currentTimeMillis() < end) {
      Thread.sleep(Math.min(1000, Math.max(1, end - System.currentTimeMillis())));
      if (System.currentTimeMillis() < end) {
        try {
          cancelled = attempts.heartbeat(claim.attemptId(), claim.leaseToken()).cancelRequested();
        } catch (ApiProblemException e) {
          events.add("heartbeat " + e.type().status().value());
          return null;
        }
        if (cancelled) {
          break;
        }
      }
    }
    var completion =
        cancelled
            ? new WorkerApi.CompletionRequest(
                claim.leaseToken(),
                "attempt-" + claim.attemptId() + "-final",
                WorkerApi.CompletionStatus.CANCELLED,
                List.of(),
                null)
            : new WorkerApi.CompletionRequest(
                claim.leaseToken(),
                "attempt-" + claim.attemptId() + "-final",
                WorkerApi.CompletionStatus.COMPLETED_WITH_RESULTS,
                claim.caseKeys().stream()
                    .map(key ->
                        new WorkerApi.CaseResult(key, CaseStatus.PASSED, 100, 0, null, null, 0))
                    .toList(),
                null);
    try {
      return attempts.complete(claim.attemptId(), completion).response().targetStatus().name();
    } catch (ApiProblemException e) {
      events.add("complete " + e.type().status().value());
      return "refused";
    }
  }

  private void ack(Channel ch, Envelope envelope) throws IOException {
    ch.basicAck(envelope.getDeliveryTag(), false);
  }

  int busySlots() {
    return slots - free.availablePermits();
  }

  Map<String, Integer> summary() {
    Map<String, Integer> out = new HashMap<>();
    for (String line : events) {
      out.merge(line.replaceAll(" attempt \\d+| gen \\d+", ""), 1, Integer::sum);
    }
    return out;
  }

  @Override
  public void close() {
    closed = true;
    pool.shutdownNow();
    try {
      if (connection != null) {
        connection.abort();
      }
    } catch (RuntimeException e) {
      // Already gone.
    }
  }
}
