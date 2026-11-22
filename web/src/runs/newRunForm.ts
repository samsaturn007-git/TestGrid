import type { CreateRunRequest, Engine, FieldError, FixtureMode, Suite } from '../api/types.ts';

// Pure form logic for the new-run dialog (PRD-05 FR-2), kept apart from the component so each
// rule has a direct test. The API checks all of it again; these checks only save a round trip.

export const ENGINES: readonly Engine[] = ['chromium', 'firefox', 'webkit'];

/** What each fixture mode makes the demo shop do (SECURITY_AND_TEST_STRATEGY fixture table). */
export const FIXTURE_DESCRIPTIONS: Record<FixtureMode, string> = {
  PASS: 'The shop behaves correctly, so the cases pass.',
  PRODUCT_DEFECT: 'The shop computes a wrong total: a reproducible FAILED with ASSERTION.',
  AUTOMATION_FLAKE: 'A seeded delay makes results flaky: FLAKY or alternating results.',
  SELECTOR_DRIFT: 'A test ID is renamed: FAILED with SELECTOR_NOT_FOUND.',
  TIMEOUT: 'The shop API stops answering: TIMED_OUT with TEST_TIMEOUT.',
};

export const SEED_MAX = 2_147_483_647;
export const DEFAULT_SEED = '42';

export interface NewRunValues {
  suiteKey: string;
  engines: Engine[];
  /** Only sent when the deployment allows fixtures and the suite declares the mode. */
  fixtureMode: FixtureMode | null;
  seed: string;
  branch: string;
  commitSha: string;
}

export type FormField = 'suite' | 'engines' | 'fixtureMode' | 'seed' | 'branch' | 'commitSha';
export type FormErrors = Partial<Record<FormField, string>>;

// The same rules as the API's CreateRunRequest (contracts §4, create-run validation).
const COMMIT_SHA = /^[0-9a-f]{7,64}$/;
const BRANCH = /^(?!.*\.\.)[A-Za-z0-9._/-]{1,200}$/;
const SEED = /^\d{1,10}$/;

/** The starting values for a suite: every allowed engine, and PASS when fixtures are on. */
export function initialValues(suite: Suite | undefined, fixturesAllowed: boolean): NewRunValues {
  return {
    suiteKey: suite?.suiteKey ?? '',
    engines: suite ? ENGINES.filter((e) => suite.allowedEngines.includes(e)) : [],
    fixtureMode: defaultFixtureMode(suite, fixturesAllowed),
    seed: DEFAULT_SEED,
    branch: '',
    commitSha: '',
  };
}

export function defaultFixtureMode(
  suite: Suite | undefined,
  fixturesAllowed: boolean,
): FixtureMode | null {
  if (!fixturesAllowed || !suite || suite.fixtureModes.length === 0) return null;
  return suite.fixtureModes.includes('PASS') ? 'PASS' : (suite.fixtureModes[0] ?? null);
}

/** Suites a run can use: current and enabled (the API answers 422 unknown-suite otherwise). */
export function runnableSuites(suites: readonly Suite[]): Suite[] {
  return suites.filter((s) => s.current && s.enabled);
}

export function validate(values: NewRunValues, suite: Suite | undefined): FormErrors {
  const errors: FormErrors = {};
  if (!suite) errors.suite = 'Choose a suite.';
  if (values.engines.length === 0) {
    errors.engines = 'Choose at least one engine.';
  } else if (suite && values.engines.some((e) => !suite.allowedEngines.includes(e))) {
    errors.engines = 'Choose only engines this suite allows.';
  }
  if (values.fixtureMode !== null) {
    if (suite && !suite.fixtureModes.includes(values.fixtureMode)) {
      errors.fixtureMode = 'This suite does not declare that fixture mode.';
    }
    const seed = values.seed.trim();
    if (!SEED.test(seed) || Number(seed) > SEED_MAX) {
      errors.seed = `Use a whole number from 0 to ${SEED_MAX.toLocaleString('en-US')}.`;
    }
  }
  const branch = values.branch.trim();
  if (branch !== '' && !BRANCH.test(branch)) {
    errors.branch = 'Use letters, digits and . _ / - only, with no "..".';
  }
  const sha = values.commitSha.trim();
  if (sha !== '' && !COMMIT_SHA.test(sha)) {
    errors.commitSha = 'Use 7 to 64 lowercase hex characters (0–9, a–f).';
  }
  return errors;
}

/**
 * The request body without the idempotency key. Empty optional fields are left out rather than
 * sent as "", which the API would reject (a branch needs at least one character).
 */
export function buildRequest(values: NewRunValues): Omit<CreateRunRequest, 'idempotencyKey'> {
  const branch = values.branch.trim();
  const commitSha = values.commitSha.trim();
  const source = {
    ...(branch ? { branch } : {}),
    ...(commitSha ? { commitSha } : {}),
  };
  return {
    suiteKey: values.suiteKey,
    // Sorted in canonical engine order, so ticking boxes in a different order is the same run.
    targets: ENGINES.filter((e) => values.engines.includes(e)).map((engine) => ({
      executor: 'LOCAL' as const,
      engine,
    })),
    trigger: 'MANUAL',
    ...(values.fixtureMode !== null
      ? { fixture: { mode: values.fixtureMode, seed: Number(values.seed.trim()) } }
      : {}),
    ...(Object.keys(source).length > 0 ? { source } : {}),
  };
}

/** Maps an API field path (`targets[1].engine`, `source.commitSha`) to the input it belongs to. */
export function formFieldFor(apiField: string): FormField | null {
  if (apiField === 'suiteKey') return 'suite';
  if (apiField === 'targets' || apiField.startsWith('targets[')) return 'engines';
  if (apiField === 'fixture' || apiField === 'fixture.mode') return 'fixtureMode';
  if (apiField === 'fixture.seed') return 'seed';
  if (apiField === 'source.branch') return 'branch';
  if (apiField === 'source.commitSha') return 'commitSha';
  return null;
}

/** Field errors from a Problem body, grouped by input; the rest stay in the dialog's alert. */
export function apiFieldErrors(errors: readonly FieldError[] | undefined): {
  byField: FormErrors;
  unmapped: FieldError[];
} {
  const byField: FormErrors = {};
  const unmapped: FieldError[] = [];
  for (const error of errors ?? []) {
    const field = formFieldFor(error.field);
    if (field && !byField[field]) byField[field] = error.message;
    else if (!field) unmapped.push(error);
  }
  return { byField, unmapped };
}

/**
 * One idempotency key per distinct request. Sending the same body again (a retry after a
 * timeout or a lost answer) reuses the key, so the API replays the first run instead of
 * starting a second one; changing anything gets a fresh key instead of a 409.
 */
export class IdempotencyKeys {
  private last: { body: string; key: string } | null = null;
  private readonly newKey: () => string;

  constructor(newKey: () => string = () => crypto.randomUUID()) {
    this.newKey = newKey;
  }

  keyFor(request: Omit<CreateRunRequest, 'idempotencyKey'>): string {
    const body = JSON.stringify(request);
    if (this.last?.body !== body) this.last = { body, key: this.newKey() };
    return this.last.key;
  }
}
