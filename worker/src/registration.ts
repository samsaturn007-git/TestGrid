import { setTimeout as sleep } from 'node:timers/promises';
import type { Logger } from 'pino';
import { ApiError, newTraceId } from './api-client.ts';
import type { Registration, WorkerApiClient } from './api-client.ts';
import { describe } from './worker.ts';

// PRD-04 FR-7: the worker's row in the registry. Register before consuming, heartbeat while
// alive (the API marks a worker silent for 3 × the lease TTL LOST), drain on SIGTERM. The
// registry is for operators; claims and leases don't depend on it, so a registry outage only
// makes the worker log and retry, never stop running attempts.

export interface RegistrationOptions {
  api: Pick<WorkerApiClient, 'register' | 'workerHeartbeat' | 'drain'>;
  log: Logger;
  registration: Registration;
  stopping: AbortSignal;
  retryMaxMs?: number;
}

export class WorkerRegistration {
  readonly #opts: RegistrationOptions;
  #id: string | null = null;
  #timer: NodeJS.Timeout | undefined;
  #draining = false;

  constructor(opts: RegistrationOptions) {
    this.#opts = opts;
  }

  get id(): string | null {
    return this.#id;
  }

  /** Registers, retrying with backoff until it succeeds or the worker stops; then heartbeats. */
  async start(): Promise<boolean> {
    let delay = 500;
    while (!this.#opts.stopping.aborted) {
      try {
        await this.#register();
        return true;
      } catch (e) {
        this.#opts.log.warn({ err: describe(e), retryInMs: delay }, 'registration failed');
        try {
          await sleep(delay, undefined, { signal: this.#opts.stopping });
        } catch {
          // Stopping.
        }
        delay = Math.min(delay * 2, this.#opts.retryMaxMs ?? 10_000);
      }
    }
    return false;
  }

  async #register(): Promise<void> {
    const registered = await this.#opts.api.register(this.#opts.registration, newTraceId());
    this.#id = registered.id;
    this.#opts.log.info(
      { registryId: registered.id, slots: this.#opts.registration.slots },
      'registered',
    );
    clearInterval(this.#timer);
    this.#timer = setInterval(() => void this.#beat(), registered.heartbeatIntervalSeconds * 1000);
    this.#timer.unref();
  }

  async #beat(): Promise<void> {
    const id = this.#id;
    if (!id) return;
    try {
      await this.#opts.api.workerHeartbeat(id, newTraceId());
    } catch (e) {
      if (e instanceof ApiError && (e.status === 410 || e.status === 404)) {
        // Marked LOST (a long pause, an API restart with a reset database): register again,
        // and drain again if this worker was already draining.
        this.#opts.log.warn({ err: describe(e) }, 'registry row lost; registering again');
        try {
          await this.#register();
          if (this.#draining) await this.drain();
        } catch (again) {
          this.#opts.log.warn({ err: describe(again) }, 'registering again failed');
        }
      } else {
        this.#opts.log.warn({ err: describe(e) }, 'worker heartbeat failed');
      }
    }
  }

  /** SIGTERM: tell the registry this worker takes no more work. Best effort, never throws. */
  async drain(): Promise<void> {
    this.#draining = true;
    const id = this.#id;
    if (!id) return;
    try {
      await this.#opts.api.drain(id, newTraceId());
      this.#opts.log.info({ registryId: id }, 'draining');
    } catch (e) {
      this.#opts.log.warn({ err: describe(e) }, 'drain call failed');
    }
  }

  stop(): void {
    clearInterval(this.#timer);
  }
}
