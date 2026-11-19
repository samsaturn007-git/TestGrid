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

// Hosts the smoke suite may target. The base URL is operator configuration
// (the future manifest `baseUrlRef: DEMO_SHOP_INTERNAL`), never client input,
// but it is still allowlisted so a stray env var can't point tests elsewhere.
const ALLOWED_HOSTS = new Set(['127.0.0.1', 'localhost', 'demo-shop']);

export interface FixtureEnv {
  mode: FixtureMode;
  seed: number;
  baseUrl: string;
}

export function parseFixtureEnv(env: Record<string, string | undefined>): FixtureEnv {
  const mode = env.FIXTURE_MODE ?? 'PASS';
  if (!(FIXTURE_MODES as readonly string[]).includes(mode)) {
    throw new Error(`FIXTURE_MODE must be one of ${FIXTURE_MODES.join(', ')}; got "${mode}"`);
  }
  const rawSeed = env.FIXTURE_SEED ?? '42';
  if (!/^\d{1,9}$/.test(rawSeed)) {
    throw new Error(`FIXTURE_SEED must be a non-negative integer below 1e9; got "${rawSeed}"`);
  }
  const baseUrl = env.DEMO_SHOP_INTERNAL ?? 'http://127.0.0.1:4173';
  const url = new URL(baseUrl);
  if (!['http:', 'https:'].includes(url.protocol) || !ALLOWED_HOSTS.has(url.hostname)) {
    throw new Error(`DEMO_SHOP_INTERNAL host "${url.hostname}" is not on the allowlist`);
  }
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('DEMO_SHOP_INTERNAL must be a bare origin');
  }
  return { mode: mode as FixtureMode, seed: Number(rawSeed), baseUrl: url.origin };
}
