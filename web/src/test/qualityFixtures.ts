import type {
  QualityOutcome,
  QualityPolicy,
  QualityPolicyView,
  QualityRuleResult,
  Quarantine,
  QuarantineList,
  RunQualityView,
  RunResponse,
} from '../api/types.ts';
import { PROJECT_ID } from './api.ts';

// PRD-08 fixtures for component tests (mocked, not live). Shapes follow contracts §12.

export const POLICY_ID = '00000000-0000-4000-8000-0000000000e1';
export const SERVER_NOW = '2026-11-24T12:00:00.000Z';

const rule = (
  ruleId: string,
  outcome: QualityOutcome,
  blocking: boolean,
  observed: number | null,
  threshold: number | null,
  evidence: QualityRuleResult['evidence'] = [],
): QualityRuleResult => ({ ruleId, observed, threshold, outcome, blocking, evidence });

export function quality(
  runId: string,
  outcome: QualityOutcome | null,
  options: { quarantined?: boolean; version?: number } = {},
): RunQualityView {
  if (outcome === null) {
    return {
      runId,
      runStatus: 'RUNNING',
      evaluationId: null,
      outcome: null,
      policy: null,
      evaluatedAt: null,
      rules: [],
    };
  }
  const cartTotal = {
    target: 'local:chromium:0',
    engine: 'chromium' as const,
    caseKey: 'cart-total',
    status: 'FAILED',
    counted: true,
    detail: 'critical case FAILED',
  };
  const excused = {
    target: 'local:chromium:0',
    engine: 'chromium' as const,
    caseKey: 'checkout-happy-path',
    status: 'QUARANTINED_FAIL',
    counted: false,
    detail:
      'FAILED; quarantined (00000000-0000-4000-8000-0000000000q1) until 2026-12-01T00:00:00Z, not counted',
  };
  const fail = outcome === 'FAIL';
  const infra = outcome === 'INDETERMINATE';
  return {
    runId,
    runStatus: fail ? 'FAILED' : infra ? 'INFRA_ERROR' : 'PASSED',
    evaluationId: '00000000-0000-4000-8000-0000000000e2',
    outcome,
    policy: { id: POLICY_ID, version: options.version ?? 3 },
    evaluatedAt: '2026-11-24T11:53:09Z',
    rules: [
      rule(
        'required-targets',
        infra ? 'INDETERMINATE' : 'PASS',
        true,
        infra ? 0 : 1,
        1,
        infra
          ? [
              {
                target: 'local:firefox:0',
                engine: 'firefox',
                caseKey: null,
                status: 'INFRA_ERROR',
                counted: false,
                detail: 'ended INFRA_ERROR (BROWSER_LAUNCH) without test results',
              },
            ]
          : [],
      ),
      rule('infra-error', infra ? 'INDETERMINATE' : 'PASS', true, infra ? 1 : 0, 0),
      rule(
        'critical-cases',
        fail ? 'FAIL' : 'PASS',
        true,
        fail ? 1 : 0,
        0,
        fail ? [cartTotal] : [],
      ),
      rule(
        'max-failed-cases',
        fail ? 'FAIL' : 'PASS',
        true,
        fail ? 1 : 0,
        0,
        fail ? [cartTotal] : [],
      ),
      rule('max-flaky-rate', 'PASS', true, 0, 0.2),
      rule('duration-regression', 'PASS', false, null, 0, [
        {
          target: null,
          engine: null,
          caseKey: null,
          status: null,
          counted: false,
          detail: 'no earlier PASSED run of this suite on this branch to compare with',
        },
      ]),
      rule(
        'quarantine-honored',
        'PASS',
        false,
        options.quarantined ? 1 : 0,
        null,
        options.quarantined ? [excused] : [],
      ),
    ],
  };
}

/** An evaluation that matches the run's own status, for pages that only need the panel quiet. */
export function qualityFor(run: RunResponse): RunQualityView {
  const terminal = ['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'];
  if (!terminal.includes(run.status)) return quality(run.id, null);
  return quality(
    run.id,
    run.status === 'PASSED' ? 'PASS' : run.status === 'FAILED' ? 'FAIL' : 'INDETERMINATE',
  );
}

export const POLICY: QualityPolicy = {
  requiredTargets: ['local:chromium'],
  failOnCriticalCase: true,
  maxFailedCases: 0,
  failOnInfraError: true,
  maxFlakyRate: 0.2,
  durationRegression: { enabled: true, maxRatio: 1.5, minDeltaMs: 500, blocking: false },
  quarantine: { honor: true, maxActive: 5, autoMode: 'SUGGEST' },
};

export function policyView(version = 2, policy: QualityPolicy = POLICY): QualityPolicyView {
  const versions = Array.from({ length: Math.max(version, 2) }, (_, i) => {
    const v = Math.max(version, 2) - i;
    return {
      id: `00000000-0000-4000-8000-0000000001${String(v).padStart(2, '0')}`,
      version: v,
      active: v === Math.max(version, 2),
      createdAt: `2026-11-${String(10 + v).padStart(2, '0')}T09:00:00Z`,
    };
  });
  const shown = versions.find((v) => v.version === version) ?? versions[0]!;
  return {
    id: shown.id,
    projectId: PROJECT_ID,
    version: shown.version,
    active: shown.active,
    createdAt: shown.createdAt,
    policy,
    versions,
  };
}

export function quarantine(overrides: Partial<Quarantine> = {}): Quarantine {
  return {
    id: '00000000-0000-4000-8000-0000000000f1',
    projectId: PROJECT_ID,
    caseKey: 'checkout-happy-path',
    engine: 'chromium',
    reason: 'Known flake from the AUTOMATION_FLAKE fixture. Fix tracked in TG-12.',
    source: 'MANUAL',
    createdBy: 'local',
    createdAt: '2026-11-20T12:00:00.000Z',
    expiresAt: '2026-12-04T12:30:00.000Z',
    revokedAt: null,
    state: 'ACTIVE',
    ...overrides,
  };
}

export function quarantineList(overrides: Partial<QuarantineList> = {}): QuarantineList {
  return {
    items: [
      quarantine(),
      quarantine({
        id: '00000000-0000-4000-8000-0000000000f2',
        caseKey: 'card-declined',
        engine: 'webkit',
        reason: 'Waiting on the selector change to land.',
        createdAt: '2026-11-11T12:00:00.000Z',
        expiresAt: '2026-11-25T18:30:00.000Z',
      }),
      quarantine({
        id: '00000000-0000-4000-8000-0000000000f3',
        caseKey: 'apply-coupon',
        engine: null,
        state: 'EXPIRED',
        createdAt: '2026-11-01T12:00:00.000Z',
        expiresAt: '2026-11-08T12:00:00.000Z',
      }),
    ],
    activeCount: 2,
    maxActive: 5,
    autoMode: 'SUGGEST',
    serverNow: SERVER_NOW,
    suggestions: [
      {
        suiteKey: 'checkout-smoke',
        caseKey: 'add-to-cart',
        engine: 'webkit',
        observed: 8,
        flakyCount: 2,
        flipRate: 0.571,
        reason: '4 flips in 7 transitions (0.57 ≥ 0.20)',
      },
    ],
    ...overrides,
  };
}
