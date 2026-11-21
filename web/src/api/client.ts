import type { Problem } from './types.ts';

const API_BASE = '/api/v1';

/**
 * A failed API call. `problem` is the RFC 9457 body when the API sent one; it is null when
 * the request never reached the API (network down, proxy error page, non-JSON body).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null) {
    super(problem ? `${problem.title}: ${problem.detail}` : `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  /** 4xx answers won't change on retry; network and 5xx failures might. */
  get retryable(): boolean {
    return this.status === 0 || this.status >= 500;
  }
}

function isProblem(body: unknown): body is Problem {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as Problem).title === 'string' &&
    typeof (body as Problem).status === 'number'
  );
}

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(API_BASE + path, {
      headers: { Accept: 'application/json' },
      ...(signal ? { signal } : {}),
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, null);
  }
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, isProblem(body) ? body : null);
  return body as T;
}
