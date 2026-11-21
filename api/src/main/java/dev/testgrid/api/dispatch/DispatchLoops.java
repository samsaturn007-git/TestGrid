package dev.testgrid.api.dispatch;

import dev.testgrid.api.worker.WorkerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the dispatcher (every {@code tick-ms}, or sooner on a nudge) and the outbox publisher (every
 * {@code outbox-poll-ms}, sooner on a nudge, straight away again after a full batch) on two daemon
 * threads, and the lease reaper (every {@code testgrid.reaper.interval-ms}) on a third. A failed
 * tick is logged once per outage and retried on the next one: the database keeps the state, so a
 * loop never has to remember anything between ticks.
 */
@Component
@ConditionalOnProperty(
    prefix = "testgrid.dispatch",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DispatchLoops implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DispatchLoops.class);

  private final Dispatcher dispatcher;
  private final OutboxPublisher publisher;
  private final Reaper reaper;
  private final WorkerRegistry registry;
  private final ReaperProperties reaperProperties;
  private final DispatchSignal signal;
  private final DispatchProperties properties;
  private final AmqpAdmin admin;
  private final AtomicBoolean running = new AtomicBoolean();
  private final List<Thread> threads = new ArrayList<>();

  public DispatchLoops(
      Dispatcher dispatcher,
      OutboxPublisher publisher,
      Reaper reaper,
      WorkerRegistry registry,
      ReaperProperties reaperProperties,
      DispatchSignal signal,
      DispatchProperties properties,
      AmqpAdmin admin) {
    this.dispatcher = dispatcher;
    this.publisher = publisher;
    this.reaper = reaper;
    this.registry = registry;
    this.reaperProperties = reaperProperties;
    this.signal = signal;
    this.properties = properties;
    this.admin = admin;
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    threads.add(Thread.ofPlatform().daemon().name("tg-dispatcher").start(this::dispatchLoop));
    threads.add(Thread.ofPlatform().daemon().name("tg-outbox").start(this::publishLoop));
    threads.add(Thread.ofPlatform().daemon().name("tg-reaper").start(this::reaperLoop));
    log.info(
        "dispatch loops started tickMs={} outboxPollMs={} outboxBatchSize={} reaperIntervalMs={}"
            + " dispatchTimeoutMs={}",
        properties.tickMs(),
        properties.outboxPollMs(),
        properties.outboxBatchSize(),
        reaperProperties.intervalMs(),
        reaperProperties.dispatchTimeoutMs());
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      try {
        thread.join(properties.confirmTimeoutMs() + 1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    threads.clear();
    log.info("dispatch loops stopped");
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void dispatchLoop() {
    boolean failing = false;
    while (running.get()) {
      try {
        dispatcher.dispatchOnce();
        if (failing) {
          log.info("dispatcher recovered");
          failing = false;
        }
      } catch (RuntimeException e) {
        if (!failing) {
          log.warn("dispatcher tick failed; retrying every tick: {}", e.toString());
          failing = true;
        }
      }
      if (!await(true, properties.tickMs())) {
        return;
      }
    }
  }

  private void publishLoop() {
    boolean failing = false;
    boolean declared = false;
    while (running.get()) {
      boolean full = false;
      try {
        if (!declared) {
          // Workers consume from queues the API owns. Declare them at startup instead of on the
          // first publish, so a worker that starts before the first run finds them; after that
          // Spring's RabbitAdmin redeclares on every new connection (a broker restart, for
          // example).
          admin.initialize();
          declared = true;
          log.info("broker topology declared");
        }
        OutboxPublisher.BatchResult result = publisher.publishBatch();
        full = result.locked() == properties.outboxBatchSize();
        if (failing && result.failed() == 0) {
          log.info("outbox publisher recovered");
          failing = false;
        }
      } catch (RuntimeException e) {
        if (!failing) {
          log.warn("outbox batch failed; retrying every poll: {}", e.toString());
          failing = true;
        }
      }
      if (!full && !await(false, properties.outboxPollMs())) {
        return;
      }
    }
  }

  private void reaperLoop() {
    boolean failing = false;
    while (running.get()) {
      boolean full = false;
      try {
        Reaper.Pass pass = reaper.reapOnce();
        registry.sweep();
        // A full batch means more may be waiting: go again straight away.
        full =
            pass.abandoned() >= reaperProperties.batchSize()
                || pass.redispatched() + pass.lost() >= reaperProperties.batchSize();
        if (failing) {
          log.info("reaper recovered");
          failing = false;
        }
      } catch (RuntimeException e) {
        if (!failing) {
          log.warn("reaper pass failed; retrying every interval: {}", e.toString());
          failing = true;
        }
      }
      if (!full && !sleep(reaperProperties.intervalMs())) {
        return;
      }
    }
  }

  private boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return running.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Waits for a nudge or the timeout; false once the loop is stopping. */
  private boolean await(boolean dispatch, long timeoutMs) {
    try {
      if (dispatch) {
        signal.awaitDispatch(timeoutMs);
      } else {
        signal.awaitPublish(timeoutMs);
      }
      return running.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
