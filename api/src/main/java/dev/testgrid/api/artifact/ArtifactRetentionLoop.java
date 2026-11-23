package dev.testgrid.api.artifact;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ArtifactRetention} every {@code testgrid.artifacts.purge-interval} (hourly) on a
 * daemon thread, the first pass a minute after start. A failed pass is logged and retried next
 * time: the database holds the state, so nothing is carried between passes.
 */
@Component
@ConditionalOnProperty(
    prefix = "testgrid.artifacts",
    name = "retention-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ArtifactRetentionLoop implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionLoop.class);
  static final Duration FIRST_PASS = Duration.ofMinutes(1);

  private final ArtifactRetention retention;
  private final ArtifactProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread thread;

  public ArtifactRetentionLoop(ArtifactRetention retention, ArtifactProperties properties) {
    this.retention = retention;
    this.properties = properties;
  }

  @Override
  public synchronized void start() {
    if (running.compareAndSet(false, true)) {
      thread = Thread.ofPlatform().daemon().name("tg-artifact-retention").start(this::loop);
      log.info(
          "artifact retention started interval={} orphanGrace={} retention={}",
          properties.purgeInterval(),
          properties.orphanGrace(),
          properties.retention());
    }
  }

  @Override
  public synchronized void stop() {
    if (running.compareAndSet(true, false) && thread != null) {
      thread.interrupt();
      try {
        thread.join(5_000);
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
    Duration wait =
        FIRST_PASS.compareTo(properties.purgeInterval()) < 0
            ? FIRST_PASS
            : properties.purgeInterval();
    while (running.get()) {
      try {
        Thread.sleep(wait.toMillis());
      } catch (InterruptedException e) {
        return;
      }
      try {
        retention.purgeOnce();
        retention.sweepOnce();
      } catch (Exception e) {
        log.warn("artifact retention pass failed; retrying next interval: {}", e.toString());
      }
      wait = properties.purgeInterval();
    }
  }
}
