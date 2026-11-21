import { hostname } from 'node:os';
import { z } from 'zod';
import { parseFixtureEnv } from '../suites/support/fixture-env.ts';
import { ENGINE } from './claim.ts';

// Worker settings come only from the operator's environment, validated at startup.
// The worker refuses to start rather than run with a half-valid configuration.

const WORKER_ID = /^[A-Za-z0-9._-]{1,64}$/;

const Env = z.object({
  TESTGRID_API_URL: z.url({ protocol: /^https?$/ }),
  TESTGRID_WORKER_TOKEN: z.string().min(32),
  TESTGRID_WORKER_ID: z.string().regex(WORKER_ID).optional(),
  TESTGRID_WORKER_ENGINES: z.string().default('chromium'),
  DEMO_SHOP_INTERNAL: z.string(),
  TESTGRID_POLL_MIN_MS: z.coerce.number().int().min(50).default(500),
  TESTGRID_POLL_MAX_MS: z.coerce.number().int().min(100).default(5000),
  TESTGRID_SHUTDOWN_GRACE_MS: z.coerce.number().int().min(0).default(20_000),
});

export interface WorkerConfig {
  apiUrl: string;
  token: string;
  workerId: string;
  engines: z.infer<typeof ENGINE>[];
  baseUrl: string;
  pollMinMs: number;
  pollMaxMs: number;
  shutdownGraceMs: number;
}

export function loadConfig(env: NodeJS.ProcessEnv): WorkerConfig {
  const parsed = Env.safeParse(env);
  if (!parsed.success) {
    // Names only: the values may include the token.
    const fields = parsed.error.issues.map((i) => i.path.join('.')).join(', ');
    throw new Error(`invalid worker configuration: ${fields}`);
  }
  const e = parsed.data;
  const api = new URL(e.TESTGRID_API_URL);
  if (api.username || api.password || api.search || api.hash || api.pathname !== '/') {
    throw new Error('TESTGRID_API_URL must be a bare origin');
  }
  const engines = [...new Set(e.TESTGRID_WORKER_ENGINES.split(',').map((s) => s.trim()))].map(
    (s) => {
      const engine = ENGINE.safeParse(s);
      if (!engine.success) throw new Error(`TESTGRID_WORKER_ENGINES: unknown engine "${s}"`);
      return engine.data;
    },
  );
  // Same allowlist the spec support applies: baseUrlRef DEMO_SHOP_INTERNAL → this origin.
  const baseUrl = parseFixtureEnv({ TG_BASE_URL: e.DEMO_SHOP_INTERNAL }).baseUrl;
  const fallbackId = `worker-${hostname()}`.replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 64);
  return {
    apiUrl: api.origin,
    token: e.TESTGRID_WORKER_TOKEN,
    workerId: e.TESTGRID_WORKER_ID ?? fallbackId,
    engines,
    baseUrl,
    pollMinMs: e.TESTGRID_POLL_MIN_MS,
    pollMaxMs: Math.max(e.TESTGRID_POLL_MAX_MS, e.TESTGRID_POLL_MIN_MS),
    shutdownGraceMs: e.TESTGRID_SHUTDOWN_GRACE_MS,
  };
}
