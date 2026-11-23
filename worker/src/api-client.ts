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

const WORKER_STATE = z.enum(['ACTIVE', 'DRAINING', 'LOST']);
const Registered = z.looseObject({
  id: z.uuid(),
  name: z.string(),
  state: WORKER_STATE,
  heartbeatIntervalSeconds: z.number().int().min(1),
});
export type Registered = z.infer<typeof Registered>;
const WorkerState = z.looseObject({ id: z.uuid(), state: WORKER_STATE });

export interface Registration {
  name: string;
  version: string;
  engines: readonly string[];
  slots: number;
}

const REQUEST_TIMEOUT_MS = 10_000;
// A 50 MB trace over the internal network; heartbeats keep the lease alive meanwhile.
const UPLOAD_TIMEOUT_MS = 60_000;

export type ArtifactKind = 'SCREENSHOT' | 'TRACE' | 'LOG';
const Uploaded = z.looseObject({ id: z.uuid(), byteSize: z.number().int().positive() });

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

  private post(path: string, body: unknown, traceId: string): Promise<Response> {
    return this.send(
      path,
      { 'content-type': 'application/json' },
      JSON.stringify(body),
      traceId,
      REQUEST_TIMEOUT_MS,
    );
  }

  private async send(
    path: string,
    headers: Record<string, string>,
    body: string | FormData,
    traceId: string,
    timeoutMs: number,
  ): Promise<Response> {
    const response = await this.#fetch(this.#baseUrl + path, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${this.#token}`,
        traceparent: `00-${traceId}-${randomBytes(8).toString('hex')}-01`,
        ...headers,
      },
      body,
      signal: AbortSignal.timeout(timeoutMs),
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

  /** PRD-04 FR-7: a new registry row; a live row with the same name becomes LOST. */
  async register(registration: Registration, traceId: string): Promise<Registered> {
    const r = await this.post(
      '/internal/v1/workers/register',
      { ...registration, executor: 'LOCAL' },
      traceId,
    );
    return Registered.parse(await r.json());
  }

  /** 410 once the row is LOST: register again. */
  async workerHeartbeat(id: string, traceId: string): Promise<string> {
    const r = await this.post(
      `/internal/v1/workers/${encodeURIComponent(id)}/heartbeat`,
      {},
      traceId,
    );
    return WorkerState.parse(await r.json()).state;
  }

  async drain(id: string, traceId: string): Promise<string> {
    const r = await this.post(`/internal/v1/workers/${encodeURIComponent(id)}/drain`, {}, traceId);
    return WorkerState.parse(await r.json()).state;
  }

  /** PRD-04 FR-5: claim the target a dispatch message names, fenced by its generation. */
  async claim(
    targetId: string,
    request: { workerId: string; messageId: string; dispatchGeneration: number },
    traceId: string,
  ): Promise<Claim> {
    const response = await this.post(
      `/internal/v1/targets/${encodeURIComponent(targetId)}/claim`,
      request,
      traceId,
    );
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

  /**
   * PRD-06 FR-1/FR-3: one artifact, uploaded under the lease before `complete`. fetch builds the
   * multipart body (one `file` part) and its boundary; the lease token goes in a header, never in
   * the URL. kind and caseKey are an enum and a manifest key, so they need no escaping beyond this.
   */
  async uploadArtifact(
    attemptId: string,
    leaseToken: string,
    upload: { kind: ArtifactKind; caseKey: string | null; file: Blob },
    traceId: string,
  ): Promise<{ id: string; byteSize: number }> {
    const form = new FormData();
    form.append('file', upload.file, 'artifact');
    const query = new URLSearchParams({ kind: upload.kind });
    if (upload.caseKey !== null) query.set('caseKey', upload.caseKey);
    const r = await this.send(
      `/internal/v1/attempts/${encodeURIComponent(attemptId)}/artifacts?${query.toString()}`,
      { 'x-testgrid-lease-token': leaseToken },
      form,
      traceId,
      UPLOAD_TIMEOUT_MS,
    );
    return Uploaded.parse(await r.json());
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
