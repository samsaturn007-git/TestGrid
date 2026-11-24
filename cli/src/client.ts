import { backoffDelays } from './backoff.ts';
import { CliError, EXIT } from './exit-codes.ts';

/** Everything the CLI touches outside itself, so tests can run it without a network or a clock. */
export interface Deps {
  fetch: typeof fetch;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  random: () => number;
  env: Readonly<Record<string, string | undefined>>;
  stdout: (text: string) => void;
  stderr: (text: string) => void;
  /** Appends to a file the caller named, such as $GITHUB_STEP_SUMMARY. */
  appendFile: (path: string, text: string) => void;
}

const REQUEST_TIMEOUT_MS = 10_000;

interface Problem {
  type?: string;
  title?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

/**
 * The public API over HTTP. Every request has a 10 s timeout and the command's deadline. Transient
 * failures (network errors, timeouts, 429 and 5xx) are retried with the polling backoff until the
 * deadline, which then answers exit 2: the API being down is an infrastructure problem, never a pass.
 * Retrying a create is safe because the idempotency key makes it a replay. Anything else the caller
 * must fix (400, 401, 403, 404, 409, 422) is exit 3 at once.
 */
export class ApiClient {
  private readonly base: string;
  private readonly token: string | null;
  private readonly deps: Deps;
  readonly deadline: number;

  constructor(apiUrl: string, token: string | null, deps: Deps, deadline: number) {
    this.base = apiUrl.replace(/\/+$/, '') + '/api/v1';
    this.token = token;
    this.deps = deps;
    this.deadline = deadline;
  }

  get<T>(path: string): Promise<ApiResponse<T>> {
    return this.request<T>('GET', path, undefined);
  }

  post<T>(path: string, body: unknown): Promise<ApiResponse<T>> {
    return this.request<T>('POST', path, body);
  }

  private async request<T>(method: string, path: string, body: unknown): Promise<ApiResponse<T>> {
    const delays = backoffDelays(this.deps.random);
    let lastProblem = 'no attempt made';
    for (;;) {
      const remaining = this.deadline - this.deps.now();
      if (remaining <= 0) {
        throw new CliError(
          EXIT.INDETERMINATE,
          `deadline reached before the API answered ${method} ${path} (${lastProblem})`,
        );
      }
      let response: Response;
      try {
        response = await this.deps.fetch(this.base + path, {
          method,
          headers: this.headers(body !== undefined),
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          redirect: 'error',
          signal: AbortSignal.timeout(Math.min(REQUEST_TIMEOUT_MS, remaining)),
        });
      } catch (error) {
        lastProblem = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
        await this.pause(delays, `${method} ${path} failed (${lastProblem})`);
        continue;
      }
      if (response.status === 429 || response.status >= 500) {
        lastProblem = `HTTP ${response.status}`;
        await response.body?.cancel();
        await this.pause(delays, `${method} ${path} answered ${response.status}`);
        continue;
      }
      const text = await response.text();
      if (response.status >= 400) {
        throw problemError(method, path, response.status, text);
      }
      return { status: response.status, body: parseJson<T>(text, method, path) };
    }
  }

  private headers(withBody: boolean): Record<string, string> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (withBody) {
      headers['Content-Type'] = 'application/json';
    }
    if (this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }
    return headers;
  }

  private async pause(delays: Generator<number>, why: string): Promise<void> {
    const delay = Math.min(
      delays.next().value as number,
      Math.max(0, this.deadline - this.deps.now()),
    );
    this.deps.stderr(`testgrid: ${why}; retrying in ${Math.round(delay / 100) / 10} s\n`);
    await this.deps.sleep(delay);
  }
}

export interface ApiResponse<T> {
  status: number;
  body: T;
}

function parseJson<T>(text: string, method: string, path: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new CliError(EXIT.INDETERMINATE, `${method} ${path} returned a body that is not JSON`);
  }
}

function problemError(method: string, path: string, status: number, text: string): CliError {
  let problem: Problem = {};
  try {
    problem = JSON.parse(text) as Problem;
  } catch {
    // Not a Problem body; the status says enough.
  }
  const slug = problem.type?.split('/').pop() ?? null;
  const fields = (problem.errors ?? []).map((e) => `${e.field}: ${e.message}`).join('; ');
  const detail = [problem.detail ?? problem.title ?? `HTTP ${status}`, fields]
    .filter(Boolean)
    .join(' — ');
  const hint = status === 401 || status === 403 ? ' (check TESTGRID_TOKEN and its scopes)' : '';
  return new CliError(EXIT.USAGE, `${method} ${path}: ${status} ${detail}${hint}`, {
    status,
    problemType: slug,
  });
}
