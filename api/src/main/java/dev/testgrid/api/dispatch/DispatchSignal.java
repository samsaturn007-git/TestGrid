package dev.testgrid.api.dispatch;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wakes the dispatcher and the publisher early (ARCHITECTURE §3: "a nudge on RUN_CREATED and on
 * every completion"). A nudge only shortens the wait; the loops still poll on their timers, so a
 * lost nudge costs one tick, never a target. Each queue holds one pending nudge at most.
 */
@Component
public class DispatchSignal {

  private final BlockingQueue<Boolean> dispatch = new ArrayBlockingQueue<>(1);
  private final BlockingQueue<Boolean> publish = new ArrayBlockingQueue<>(1);

  /** Nudge the dispatcher once the caller's transaction commits (now, if there is none). */
  public void dispatchAfterCommit() {
    afterCommit(() -> dispatch.offer(Boolean.TRUE));
  }

  /** Nudge the publisher once the caller's transaction commits (now, if there is none). */
  public void publishAfterCommit() {
    afterCommit(() -> publish.offer(Boolean.TRUE));
  }

  boolean awaitDispatch(long timeoutMs) throws InterruptedException {
    return dispatch.poll(timeoutMs, TimeUnit.MILLISECONDS) != null;
  }

  boolean awaitPublish(long timeoutMs) throws InterruptedException {
    return publish.poll(timeoutMs, TimeUnit.MILLISECONDS) != null;
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
