// Mirrors FixtureMode in contracts §2.1 (and demo-shop/src/server/fixture-mode.ts).
// PRD-03 moves the shared list into packages/contracts with the manifest schema.
export const FIXTURE_MODES = [
  'PASS',
  'PRODUCT_DEFECT',
  'AUTOMATION_FLAKE',
  'SELECTOR_DRIFT',
  'TIMEOUT',
] as const;
export type FixtureMode = (typeof FIXTURE_MODES)[number];

// Hosts a suite may target. The worker resolves the manifest's `baseUrlRef` to
// TG_BASE_URL (operator configuration, never client input); it is allowlisted
// here as well so a stray env var can't point tests elsewhere.
const ALLOWED_HOSTS = new Set(['127.0.0.1', 'localhost', 'demo-shop']);

export const MAX_SEED = 2 ** 31 - 1;

export interface FixtureEnv {
  mode: FixtureMode;
  seed: number;
  baseUrl: string;
}

export function parseFixtureEnv(env: Record<string, string | undefined>): FixtureEnv {
  const mode = env.TG_FIXTURE_MODE ?? 'PASS';
  if (!(FIXTURE_MODES as readonly string[]).includes(mode)) {
    throw new Error(`TG_FIXTURE_MODE must be one of ${FIXTURE_MODES.join(', ')}; got "${mode}"`);
  }
  const rawSeed = env.TG_FIXTURE_SEED ?? '42';
  // The API accepts seeds 0 … 2³¹−1 (contracts §4) and so does the shop; a narrower rule here made
  // every run with a seed of 1e9 or more fail before it started (CONFLICT_REGISTER N31).
  if (!/^\d{1,10}$/.test(rawSeed) || Number(rawSeed) > MAX_SEED) {
    throw new Error(`TG_FIXTURE_SEED must be an integer 0 … ${MAX_SEED}; got "${rawSeed}"`);
  }
  const baseUrl = env.TG_BASE_URL ?? 'http://127.0.0.1:4173';
  const url = new URL(baseUrl);
  if (!['http:', 'https:'].includes(url.protocol) || !ALLOWED_HOSTS.has(url.hostname)) {
    throw new Error(`TG_BASE_URL host "${url.hostname}" is not on the allowlist`);
  }
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('TG_BASE_URL must be a bare origin');
  }
  return { mode: mode as FixtureMode, seed: Number(rawSeed), baseUrl: url.origin };
}
