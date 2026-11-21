import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import type { Completion } from './attempt.ts';
import { Claim } from './claim.ts';

// Client for the internal worker API (contracts §7). Every call carries the service token
// and a W3C traceparent, so API log lines for one attempt share the worker's traceId.

export class ApiError extends Error {
  override name = 'ApiError';
  readonly status: number;
  readonly problemType: string | null;

  constructor(status: number, problemType: string | null, detail: string) {
    super(detail);
    this.status = status;
    this.problemType = problemType;
  }
}

const Problem = z.looseObject({ type: z.string().optional(), detail: z.string().optional() });
const Heartbeat = z.looseObject({ leasedUntil: z.string(), cancelRequested: z.boolean() });
const Started = z.looseObject({ status: z.literal('RUNNING'), leasedUntil: z.string() });
const Completed = z.looseObject({
  attemptStatus: z.string(),
  targetStatus: z.string(),
  runStatus: z.string(),
  errorClass: z.string().nullable(),
});
export type CompletedResponse = z.infer<typeof Completed>;

const REQUEST_TIMEOUT_MS = 10_000;

export function newTraceId(): string {
  return randomBytes(16).toString('hex');
}

export class WorkerApiClient {
  readonly #baseUrl: string;
  readonly #token: string;
  readonly #fetch: typeof fetch;

  constructor(baseUrl: string, token: string, fetchImpl: typeof fetch = fetch) {
    this.#baseUrl = baseUrl;
    this.#token = token;
    this.#fetch = fetchImpl;
  }

  private async post(path: string, body: unknown, traceId: string): Promise<Response> {
    const response = await this.#fetch(this.#baseUrl + path, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${this.#token}`,
        'content-type': 'application/json',
        traceparent: `00-${traceId}-${randomBytes(8).toString('hex')}-01`,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      redirect: 'error',
    });
    if (response.status >= 400) {
      const problem = Problem.safeParse(await response.json().catch(() => ({})));
      const type = problem.success ? (problem.data.type?.split('/').pop() ?? null) : null;
      throw new ApiError(
        response.status,
        type,
        `${path} answered ${response.status}${type ? ` ${type}` : ''}`,
      );
    }
    return response;
  }

  async claimNext(
    workerId: string,
    engines: readonly string[],
    traceId: string,
  ): Promise<Claim | null> {
    const response = await this.post(
      '/internal/v1/claims/next',
      { workerId, executor: 'LOCAL', engines },
      traceId,
    );
    if (response.status === 204) return null;
    return Claim.parse(await response.json());
  }

  async start(attemptId: string, leaseToken: string, traceId: string): Promise<void> {
    const r = await this.post(`/internal/v1/attempts/${attemptId}/start`, { leaseToken }, traceId);
    Started.parse(await r.json());
  }

  async heartbeat(attemptId: string, leaseToken: string, traceId: string) {
    const r = await this.post(
      `/internal/v1/attempts/${attemptId}/heartbeat`,
      { leaseToken },
      traceId,
    );
    return Heartbeat.parse(await r.json());
  }

  async complete(
    attemptId: string,
    leaseToken: string,
    completion: Completion,
    traceId: string,
  ): Promise<CompletedResponse> {
    const r = await this.post(
      `/internal/v1/attempts/${attemptId}/complete`,
      { leaseToken, completionKey: `attempt-${attemptId}-final`, ...completion },
      traceId,
    );
    return Completed.parse(await r.json());
  }
}
