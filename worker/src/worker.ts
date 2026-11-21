import { setTimeout as sleep } from 'node:timers/promises';
import type { Logger } from 'pino';
import { ApiError, newTraceId } from './api-client.ts';
import type { WorkerApiClient } from './api-client.ts';
import { executeAttempt } from './attempt.ts';
import type { AttemptDeps, Completion } from './attempt.ts';
import type { Claim } from './claim.ts';

// FR-5/FR-6 runtime: poll for a claim, run it with heartbeats, report it. One attempt at a
// time (single slot until PRD-04). The database decides every state; the worker only asks.

export interface WorkerOptions {
  api: Pick<WorkerApiClient, 'claimNext' | 'start' | 'heartbeat' | 'complete'>;
  log: Logger;
  workerId: string;
  engines: readonly string[];
  attemptDeps: AttemptDeps;
  pollMinMs: number;
  pollMaxMs: number;
  shutdownGraceMs: number;
  /** Aborted on the worker's own SIGTERM: stop claiming, give the current attempt the grace. */
  stopping: AbortSignal;
  /** Test hook: run the loop for at most this many attempts. */
  maxAttempts?: number;
  execute?: typeof executeAttempt;
}

const COMPLETE_RETRIES = 5;

function jitter(ms: number): number {
  return Math.round(ms * (0.8 + Math.random() * 0.4));
}

async function pause(ms: number, signal: AbortSignal): Promise<void> {
  try {
    await sleep(ms, undefined, { signal });
  } catch {
    // Woken early by shutdown.
  }
}

export async function runWorker(opts: WorkerOptions): Promise<void> {
  let idle = opts.pollMinMs;
  let attempts = 0;
  opts.log.info({ engines: opts.engines }, 'worker started');
  while (!opts.stopping.aborted && attempts < (opts.maxAttempts ?? Infinity)) {
    const traceId = newTraceId();
    let claim: Claim | null;
    try {
      claim = await opts.api.claimNext(opts.workerId, opts.engines, traceId);
    } catch (e) {
      opts.log.warn({ traceId, err: describe(e) }, 'claim failed; backing off');
      await pause(jitter(opts.pollMaxMs), opts.stopping);
      continue;
    }
    if (!claim) {
      await pause(jitter(idle), opts.stopping);
      idle = Math.min(idle * 2, opts.pollMaxMs);
      continue;
    }
    idle = opts.pollMinMs;
    attempts++;
    await runAttempt(opts, claim, traceId);
  }
  opts.log.info('worker stopped claiming');
}

async function runAttempt(opts: WorkerOptions, claim: Claim, traceId: string): Promise<void> {
  const log = opts.log.child({
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
    await opts.api.start(claim.attemptId, claim.leaseToken, traceId);
  } catch (e) {
    log.warn({ err: describe(e) }, 'start rejected; dropping the claim');
    return;
  }

  const abort = new AbortController();
  const heartbeat = setInterval(() => {
    opts.api.heartbeat(claim.attemptId, claim.leaseToken, traceId).then(
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

  // FR-6: on the worker's own SIGTERM the attempt gets the grace period, then is stopped.
  let graceTimer: NodeJS.Timeout | undefined;
  const onStopping = () => {
    log.info({ graceMs: opts.shutdownGraceMs }, 'shutdown: waiting for the current attempt');
    graceTimer = setTimeout(() => abort.abort('shutdown'), opts.shutdownGraceMs);
  };
  if (opts.stopping.aborted) onStopping();
  else opts.stopping.addEventListener('abort', onStopping, { once: true });

  let completion: Completion;
  try {
    completion = await (opts.execute ?? executeAttempt)(claim, opts.attemptDeps, abort.signal);
  } finally {
    clearInterval(heartbeat);
    clearTimeout(graceTimer);
    opts.stopping.removeEventListener('abort', onStopping);
  }

  if (abort.signal.reason === 'lease-lost') {
    // The attempt is no longer ours; a completion would only be refused with 410.
    log.warn('not completing an attempt whose lease was lost');
    return;
  }
  log.info(
    {
      status: completion.status,
      cases: completion.caseResults.length,
      errorClass: completion.workerError?.errorClass ?? null,
    },
    'attempt finished; reporting',
  );
  await report(opts, claim, completion, traceId, log);
}

async function report(
  opts: WorkerOptions,
  claim: Claim,
  completion: Completion,
  traceId: string,
  log: Logger,
): Promise<void> {
  // The completion key is fixed per attempt, so a retry after a lost response is a replay.
  for (let i = 1; i <= COMPLETE_RETRIES; i++) {
    try {
      const result = await opts.api.complete(
        claim.attemptId,
        claim.leaseToken,
        completion,
        traceId,
      );
      log.info(result, 'completion accepted');
      return;
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) {
        log.error({ err: describe(e) }, 'completion refused');
        return;
      }
      log.warn({ err: describe(e), try: i }, 'completion failed; retrying');
      await sleep(Math.min(500 * 2 ** i, 8000));
    }
  }
  log.error('completion not delivered; the lease will expire and the target stays non-terminal');
}

function describe(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
}
