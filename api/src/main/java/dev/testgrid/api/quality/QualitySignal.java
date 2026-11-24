package dev.testgrid.api.quality;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Hands a finished run to the quality loop once the finishing transaction commits. Only a nudge: a
 * full queue drops the ID, a rolled-back transaction never sends it, and the loop's sweep picks up
 * any finished run that still has no evaluation.
 */
@Component
public class QualitySignal {

  private final BlockingQueue<UUID> finished = new ArrayBlockingQueue<>(1000);

  public void evaluateAfterCommit(UUID runId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              finished.offer(runId);
            }
          });
    } else {
      finished.offer(runId);
    }
  }

  UUID await(long timeoutMs) throws InterruptedException {
    return finished.poll(timeoutMs, TimeUnit.MILLISECONDS);
  }
}
