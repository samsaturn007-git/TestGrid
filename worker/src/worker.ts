import { setTimeout as sleep } from 'node:timers/promises';
import type { Logger } from 'pino';
import { ApiError, newTraceId } from './api-client.ts';
import type { WorkerApiClient } from './api-client.ts';
import { executeAttempt } from './attempt.ts';
import type { AttemptDeps, Completion } from './attempt.ts';
import type { Claim } from './claim.ts';
import { parseDispatchMessage, traceIdOf } from './dispatch-message.ts';

// PRD-04 FR-5: one dispatch message in, one decision out. The database decides every state;
// the worker only asks, runs what it was given and reports. What happens to the message
// (ack, retry queue, dead letter) is decided here and carried out by the consumer.

export interface DeliveryDeps {
  api: Pick<WorkerApiClient, 'claim' | 'start' | 'heartbeat' | 'complete'>;
  log: Logger;
  workerId: string;
  attemptDeps: AttemptDeps;
  shutdownGraceMs: number;
  /** Aborted on the worker's own SIGTERM: the current attempt gets the grace period. */
  stopping: AbortSignal;
  execute?: typeof executeAttempt;
  /**
   * PRD-04 Verification fault points (TG_FAULT, local profile only). In the real worker the hook
   * kills the process with SIGKILL at that point; tests pass a recorder.
   */
  fault?: (point: FaultPoint) => void;
}

export type FaultPoint = 'after_claim' | 'after_complete_before_ack';

export type Decision =
  /** The database has the outcome (or never will need this message): remove it. */
  | { action: 'ack'; reason: string }
  /** Not now (capacity, API unreachable): park it in the retry queue with a TTL. */
  | { action: 'retry'; reason: string }
  /** Can never be processed (unparseable, refused as malformed): to tg.targets.dead. */
  | { action: 'dead-letter'; reason: string };

const COMPLETE_RETRIES = 5;

export async function handleDelivery(
  deps: DeliveryDeps,
  body: Buffer,
  traceparent: unknown,
): Promise<Decision> {
  const message = parseDispatchMessage(body);
  if (!message) {
    deps.log.error({ bytes: body.length }, 'unparseable dispatch message; dead-lettering');
    return { action: 'dead-letter', reason: 'unparseable' };
  }
  // FR-9: continue the dispatcher's trace, so API and worker lines for this attempt share it.
  const traceId = traceIdOf(traceparent) ?? newTraceId();
  const log = deps.log.child({
    targetId: message.targetId,
    messageId: message.messageId,
    dispatchGeneration: message.dispatchGeneration,
    traceId,
  });

  let claim: Claim;
  try {
    claim = await deps.api.claim(
      message.targetId,
      {
        workerId: deps.workerId,
        messageId: message.messageId,
        dispatchGeneration: message.dispatchGeneration,
      },
      traceId,
    );
  } catch (e) {
    return claimRefused(e, log);
  }
  deps.fault?.('after_claim');
  const completed = await runAttempt(deps, claim, traceId);
  if (completed) deps.fault?.('after_complete_before_ack');
  // Ack only now: the completion was accepted, refused, or will be recovered by the lease
  // reaper (which dispatches a new generation); this message can't do anything more.
  return { action: 'ack', reason: 'attempt finished' };
}

function claimRefused(e: unknown, log: Logger): Decision {
  if (!(e instanceof ApiError)) {
    log.warn({ err: describe(e) }, 'claim failed; parking the message in the retry queue');
    return { action: 'retry', reason: 'api unreachable' };
  }
  if (e.status === 409) {
    // Redelivery, requeue or cancel: a newer message exists or none is needed (contracts §9).
    log.info({ problem: e.problemType }, 'stale dispatch; acking');
    return { action: 'ack', reason: 'stale' };
  }
  if (e.status === 429) {
    log.info('at the global session cap; parking the message in the retry queue');
    return { action: 'retry', reason: 'capacity' };
  }
  if (e.status === 404) {
    log.error('the target does not exist; acking');
    return { action: 'ack', reason: 'unknown target' };
  }
  if (e.status === 401 || e.status === 403 || e.status >= 500) {
    // A configuration or API problem, not a bad message: keep it.
    log.warn({ err: describe(e) }, 'claim failed; parking the message in the retry queue');
    return { action: 'retry', reason: `api ${e.status}` };
  }
  log.error({ err: describe(e) }, 'claim refused as malformed; dead-lettering');
  return { action: 'dead-letter', reason: `api ${e.status}` };
}

/** True when the API accepted the completion (a replay included). */
async function runAttempt(deps: DeliveryDeps, claim: Claim, traceId: string): Promise<boolean> {
  const log = deps.log.child({
    runId: claim.runId,
    targetId: claim.targetId,
    attemptId: claim.attemptId,
    // workerId is already on every line through the logger's base fields.
    traceId,
  });
  log.info(
    { suiteKey: claim.suite.suiteKey, engine: claim.engine, attemptNo: claim.attemptNo },
    'claimed',
  );
  try {
    await deps.api.start(claim.attemptId, claim.leaseToken, traceId);
  } catch (e) {
    log.warn({ err: describe(e) }, 'start rejected; dropping the claim');
    return false;
  }

  const abort = new AbortController();
  const heartbeat = setInterval(() => {
    deps.api.heartbeat(claim.attemptId, claim.leaseToken, traceId).then(
      (beat) => {
        if (beat.cancelRequested && !abort.signal.aborted) {
          log.info('cancel requested; stopping the run');
          abort.abort('cancelled');
        }
      },
      (e: unknown) => {
        if (e instanceof ApiError && (e.status === 410 || e.status === 409)) {
          log.error({ err: describe(e) }, 'lease lost; stopping the run');
          abort.abort('lease-lost');
        } else {
          // A transient failure: keep running, the next beat may succeed before the TTL.
          log.warn({ err: describe(e) }, 'heartbeat failed');
        }
      },
    );
  }, claim.heartbeatIntervalSeconds * 1000);

  // FR-6 (PRD-03): on the worker's own SIGTERM the attempt gets the grace period, then stops.
  let graceTimer: NodeJS.Timeout | undefined;
  const onStopping = () => {
    log.info({ graceMs: deps.shutdownGraceMs }, 'shutdown: waiting for the current attempt');
    graceTimer = setTimeout(() => abort.abort('shutdown'), deps.shutdownGraceMs);
  };
  if (deps.stopping.aborted) onStopping();
  else deps.stopping.addEventListener('abort', onStopping, { once: true });

  let completion: Completion;
  try {
    completion = await (deps.execute ?? executeAttempt)(claim, deps.attemptDeps, abort.signal);
  } finally {
    clearInterval(heartbeat);
    clearTimeout(graceTimer);
    deps.stopping.removeEventListener('abort', onStopping);
  }

  if (abort.signal.reason === 'lease-lost') {
    // The attempt is no longer ours; a completion would only be refused with 410.
    log.warn('not completing an attempt whose lease was lost');
    return false;
  }
  log.info(
    {
      status: completion.status,
      cases: completion.caseResults.length,
      errorClass: completion.workerError?.errorClass ?? null,
    },
    'attempt finished; reporting',
  );
  return report(deps, claim, completion, traceId, log);
}

async function report(
  deps: DeliveryDeps,
  claim: Claim,
  completion: Completion,
  traceId: string,
  log: Logger,
): Promise<boolean> {
  // The completion key is fixed per attempt, so a retry after a lost response is a replay.
  for (let i = 1; i <= COMPLETE_RETRIES; i++) {
    try {
      const result = await deps.api.complete(
        claim.attemptId,
        claim.leaseToken,
        completion,
        traceId,
      );
      log.info(result, 'completion accepted');
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) {
        log.error({ err: describe(e) }, 'completion refused');
        return false;
      }
      log.warn({ err: describe(e), try: i }, 'completion failed; retrying');
      await sleep(Math.min(500 * 2 ** i, 8000));
    }
  }
  log.error('completion not delivered; the lease will expire and the reaper takes over');
  return false;
}

export function describe(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
}
