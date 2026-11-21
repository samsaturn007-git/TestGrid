package dev.testgrid.api.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the dispatcher (every {@code tick-ms}, or sooner on a nudge) and the outbox publisher (every
 * {@code outbox-poll-ms}, sooner on a nudge, straight away again after a full batch) on two daemon
 * threads. A failed tick is logged once per outage and retried on the next one: the database keeps
 * the state, so a loop never has to remember anything between ticks.
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
  private final DispatchSignal signal;
  private final DispatchProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();
  private final List<Thread> threads = new ArrayList<>();

  public DispatchLoops(
      Dispatcher dispatcher,
      OutboxPublisher publisher,
      DispatchSignal signal,
      DispatchProperties properties) {
    this.dispatcher = dispatcher;
    this.publisher = publisher;
    this.signal = signal;
    this.properties = properties;
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    threads.add(Thread.ofPlatform().daemon().name("tg-dispatcher").start(this::dispatchLoop));
    threads.add(Thread.ofPlatform().daemon().name("tg-outbox").start(this::publishLoop));
    log.info(
        "dispatch loops started tickMs={} outboxPollMs={} outboxBatchSize={}",
        properties.tickMs(),
        properties.outboxPollMs(),
        properties.outboxBatchSize());
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
    while (running.get()) {
      boolean full = false;
      try {
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
