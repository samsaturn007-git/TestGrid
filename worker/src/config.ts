import { hostname } from 'node:os';
import { z } from 'zod';
import { parseFixtureEnv } from '../suites/support/fixture-env.ts';
import { ENGINE } from './claim.ts';
import type { FaultPoint } from './worker.ts';

// Worker settings come only from the operator's environment, validated at startup.
// The worker refuses to start rather than run with a half-valid configuration.

const WORKER_ID = /^[A-Za-z0-9._-]{1,64}$/;

const Env = z.object({
  TESTGRID_API_URL: z.url({ protocol: /^https?$/ }),
  TESTGRID_WORKER_TOKEN: z.string().min(32),
  TESTGRID_WORKER_ID: z.string().regex(WORKER_ID).optional(),
  TESTGRID_WORKER_ENGINES: z.string().default('chromium'),
  // Shown in the registry (GET /api/v1/workers); the image build can set it.
  TESTGRID_WORKER_VERSION: z
    .string()
    .regex(/^[A-Za-z0-9._+-]{1,64}$/)
    .default('dev'),
  DEMO_SHOP_INTERNAL: z.string(),
  // PRD-04 FR-5: the broker the dispatch messages come from. Operator settings, never client input.
  RABBITMQ_HOST: z
    .string()
    .regex(/^[A-Za-z0-9.-]{1,253}$/)
    .default('rabbitmq'),
  RABBITMQ_PORT: z.coerce.number().int().min(1).max(65535).default(5672),
  RABBITMQ_USER: z.string().min(1).max(128),
  RABBITMQ_PASSWORD: z.string().min(1).max(256),
  // prefetch = slots (contracts §9); docs/VERSIONS.md §3 allows 1 by default, 2 at most for now.
  TESTGRID_WORKER_SLOTS: z.coerce.number().int().min(1).max(16).default(1),
  // PRD-04 Verification fault points; refused outside the local profile, like the API's.
  TESTGRID_PROFILE: z.string().optional(),
  TG_FAULT: z
    .enum([
      '',
      'after_claim',
      'after_complete_before_ack',
      'after_publish_before_mark',
      'browser_launch',
    ])
    .default(''),
  // PRD-06 AC-4 canary drill (scripts/scan-canary.sh); refused outside the local profile.
  TESTGRID_CANARY_SECRET: z
    .union([z.literal(''), z.string().regex(/^[A-Za-z0-9._-]{16,128}$/)])
    .default(''),
  TESTGRID_RECONNECT_MAX_MS: z.coerce.number().int().min(100).default(30_000),
  TESTGRID_SHUTDOWN_GRACE_MS: z.coerce.number().int().min(0).default(20_000),
});

export interface WorkerConfig {
  apiUrl: string;
  token: string;
  workerId: string;
  version: string;
  engines: z.infer<typeof ENGINE>[];
  baseUrl: string;
  amqp: AmqpConfig;
  slots: number;
  reconnectMaxMs: number;
  shutdownGraceMs: number;
  /** A worker-side fault point to fire, or null. */
  fault: FaultPoint | null;
  /**
   * PRD-07 FR-8 (CONFLICT_REGISTER N27, reused by PRD-10 D10): every attempt's browser fails to
   * launch for real, so the classifier sees a genuine BROWSER_LAUNCH, not a fabricated one.
   */
  browserLaunchFault: boolean;
  /** The AC-4 canary, put into the page by the spec support; null outside a drill. */
  canary: string | null;
}

export interface AmqpConfig {
  hostname: string;
  port: number;
  username: string;
  password: string;
}

export function loadConfig(env: NodeJS.ProcessEnv): WorkerConfig {
  const parsed = Env.safeParse(env);
  if (!parsed.success) {
    // Names only: the values may include the token.
    const fields = parsed.error.issues.map((i) => i.path.join('.')).join(', ');
    throw new Error(`invalid worker configuration: ${fields}`);
  }
  const e = parsed.data;
  if (e.TG_FAULT !== '' && e.TESTGRID_PROFILE !== 'local') {
    throw new Error('TG_FAULT is only honoured with TESTGRID_PROFILE=local');
  }
  if (e.TESTGRID_CANARY_SECRET !== '' && e.TESTGRID_PROFILE !== 'local') {
    throw new Error('TESTGRID_CANARY_SECRET is only honoured with TESTGRID_PROFILE=local');
  }
  // after_publish_before_mark is the API's point; nothing to do in the worker.
  const fault: FaultPoint | null =
    e.TG_FAULT === 'after_claim' || e.TG_FAULT === 'after_complete_before_ack' ? e.TG_FAULT : null;
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
    version: e.TESTGRID_WORKER_VERSION,
    engines,
    baseUrl,
    amqp: {
      hostname: e.RABBITMQ_HOST,
      port: e.RABBITMQ_PORT,
      username: e.RABBITMQ_USER,
      password: e.RABBITMQ_PASSWORD,
    },
    slots: e.TESTGRID_WORKER_SLOTS,
    reconnectMaxMs: e.TESTGRID_RECONNECT_MAX_MS,
    fault,
    browserLaunchFault: e.TG_FAULT === 'browser_launch',
    shutdownGraceMs: e.TESTGRID_SHUTDOWN_GRACE_MS,
    canary: e.TESTGRID_CANARY_SECRET === '' ? null : e.TESTGRID_CANARY_SECRET,
  };
}
