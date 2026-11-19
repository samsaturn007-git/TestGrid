// Canonical FixtureMode values (contracts §2.1). Spelling must match exactly.
export const FIXTURE_MODES = [
  'PASS',
  'PRODUCT_DEFECT',
  'AUTOMATION_FLAKE',
  'SELECTOR_DRIFT',
  'TIMEOUT',
] as const;

export type FixtureMode = (typeof FIXTURE_MODES)[number];
