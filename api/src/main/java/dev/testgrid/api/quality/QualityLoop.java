package dev.testgrid.api.quality;

import dev.testgrid.api.quarantine.QuarantineService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * One daemon thread: evaluates each run the signal hands over, and sweeps for missed ones whenever
 * the queue stays quiet for a sweep interval. Evaluation is idempotent, so the same run arriving
 * twice, or from the loop and a GET at once, stores one evaluation.
 */
@Component
@ConditionalOnProperty(
    prefix = "testgrid.quality",
    name = "loop-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class QualityLoop implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(QualityLoop.class);

  private final QualitySignal signal;
  private final QualityEvaluator evaluator;
  private final QualityEvaluationRepository evaluations;
  private final QualityProperties properties;
  private final QuarantineService quarantines;
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread thread;

  public QualityLoop(
      QualitySignal signal,
      QualityEvaluator evaluator,
      QualityEvaluationRepository evaluations,
      QualityProperties properties,
      QuarantineService quarantines) {
    this.signal = signal;
    this.evaluator = evaluator;
    this.evaluations = evaluations;
    this.properties = properties;
    this.quarantines = quarantines;
  }

  @Override
  public synchronized void start() {
    if (running.compareAndSet(false, true)) {
      thread = Thread.ofPlatform().daemon().name("tg-quality").start(this::loop);
      log.info(
          "quality loop started sweepIntervalMs={} sweepBatchSize={}",
          properties.sweepIntervalMs(),
          properties.sweepBatchSize());
    }
  }

  @Override
  public synchronized void stop() {
    if (running.compareAndSet(true, false)) {
      thread.interrupt();
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void loop() {
    long interval = properties.sweepIntervalMs();
    long nextSweep = System.nanoTime();
    while (running.get()) {
      try {
        long waitMs = Math.max(0, (nextSweep - System.nanoTime()) / 1_000_000);
        UUID runId = signal.await(waitMs);
        if (runId != null) {
          evaluateLogged(runId);
        }
        // Sweep on a timer, not only when the queue is quiet, so steady traffic can't starve it.
        if (System.nanoTime() - nextSweep >= 0) {
          sweep();
          nextSweep = System.nanoTime() + interval * 1_000_000;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void sweep() {
    try {
      quarantines.recordExpiries();
    } catch (RuntimeException e) {
      log.warn("quarantine expiry pass failed; retrying next interval: {}", e.toString());
    }
    try {
      evaluations
          .unevaluatedFinishedRuns(properties.sweepBatchSize())
          .forEach(this::evaluateLogged);
    } catch (RuntimeException e) {
      log.warn("quality sweep failed; retrying next interval: {}", e.toString());
    }
  }

  /** One run's failure is logged and left for the next sweep; it never stops the others. */
  private void evaluateLogged(UUID runId) {
    try {
      evaluator.evaluate(runId);
    } catch (RuntimeException e) {
      log.warn(
          "quality evaluation of run {} failed; the sweep retries it: {}", runId, e.toString());
    }
  }
}
