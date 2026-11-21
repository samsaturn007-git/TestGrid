import { z } from 'zod';

// The claim payload from POST /internal/v1/targets/{targetId}/claim (contracts §7), validated before the
// worker acts on it: the API is trusted, but a bug there must not become a Playwright flag.
export const ENGINE = z.enum(['chromium', 'firefox', 'webkit']);
export const FIXTURE_MODE = z.enum([
  'PASS',
  'PRODUCT_DEFECT',
  'AUTOMATION_FLAKE',
  'SELECTOR_DRIFT',
  'TIMEOUT',
]);
const KEY = /^[a-z0-9]+(-[a-z0-9]+)*$/;

export const Claim = z.strictObject({
  attemptId: z.uuid(),
  attemptNo: z.number().int().min(1),
  runId: z.uuid(),
  targetId: z.uuid(),
  leaseToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  leaseTtlSeconds: z.number().int().min(2),
  heartbeatIntervalSeconds: z.number().int().min(1),
  suite: z.strictObject({
    suiteKey: z.string().regex(KEY).max(64),
    revision: z.string().regex(/^[0-9a-f]{12}$/),
  }),
  engine: ENGINE,
  caseKeys: z.array(z.string().regex(KEY).max(64)).min(1).max(500),
  fixture: z.strictObject({ mode: FIXTURE_MODE, seed: z.number().int().min(0) }).nullable(),
  timeoutMs: z.number().int().min(1000),
});
export type Claim = z.infer<typeof Claim>;
