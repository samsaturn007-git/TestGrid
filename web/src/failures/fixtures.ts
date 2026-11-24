import type { FailureGroupSummary, FailureGroupView } from '../api/types.ts';

// Fake API bodies for the failure pages' component tests (mocked, not live).

export const GROUP_ID = '7f000000-0000-4000-8000-000000000001';
export const RUN_ID = '355f5da2-0000-4000-8000-000000000002';

export function summary(overrides: Partial<FailureGroupSummary> = {}): FailureGroupSummary {
  return {
    id: GROUP_ID,
    algoVersion: 'fp_v1',
    fingerprint: '31e12582bd8cb543',
    errorClass: 'ASSERTION',
    category: 'PRODUCT_SUSPECTED',
    ruleCategory: 'PRODUCT_SUSPECTED',
    ruleId: 'R5',
    confidence: 0.74,
    overridden: false,
    normalisedMessage:
      "Error: expect(locator).toHaveText(expected) failed\nLocator: getByTestId('cart-total')",
    occurrenceCount: 3,
    runCount: 1,
    engines: ['chromium', 'firefox', 'webkit'],
    caseKeys: ['cart-total'],
    wholeTarget: false,
    firstSeenAt: '2026-11-24T10:00:00Z',
    lastSeenAt: '2026-11-24T11:00:00Z',
    links: { self: `/api/v1/failure-groups/${GROUP_ID}` },
    ...overrides,
  };
}

export function group(overrides: Partial<FailureGroupView> = {}): FailureGroupView {
  return {
    id: GROUP_ID,
    algoVersion: 'fp_v1',
    fingerprint: '31e12582bd8cb543',
    errorClass: 'ASSERTION',
    category: 'PRODUCT_SUSPECTED',
    ruleCategory: 'PRODUCT_SUSPECTED',
    ruleId: 'R5',
    confidence: 0.74,
    normalisedMessage:
      'Error: expect(locator).toHaveText(expected) failed\nExpected: "$<N>.<N>"\nReceived: "$<N>.<N>"',
    occurrenceCount: 3,
    firstSeenAt: '2026-11-24T10:00:00Z',
    lastSeenAt: '2026-11-24T11:00:00Z',
    override: null,
    evidence: {
      ruleId: 'R5',
      ruleName: 'assertion-reproduced',
      category: 'PRODUCT_SUSPECTED',
      explanation:
        'The same assertion failed in at least two attempts and never passed on a retry, so the product may be wrong.',
      facts: {
        errorClass: 'ASSERTION',
        occurrences: 3,
        attempts: 3,
        runs: 1,
        engines: 3,
        passedOnRetry: 0,
        statusesSeen: ['FAILED'],
        casesLabelledFlaky: [],
      },
      rulesTried: [
        {
          ruleId: 'R1',
          name: 'selector-not-found',
          category: 'TEST_BROKEN',
          matched: false,
          reason: 'error class ASSERTION is not [SELECTOR_NOT_FOUND]',
        },
        {
          ruleId: 'R5',
          name: 'assertion-reproduced',
          category: 'PRODUCT_SUSPECTED',
          matched: true,
          reason: 'error class ASSERTION, seen in 3 attempts across 1 run(s)',
        },
      ],
      confidence: {
        base: 0.7,
        occurrenceBonus: 0.04,
        agreement: 1,
        disagreementPenalty: 0,
        floor: 0.05,
        cap: 0.95,
        value: 0.74,
      },
    },
    occurrences: [
      {
        runId: RUN_ID,
        targetId: '00000000-0000-4000-8000-000000000001',
        attemptId: '00000000-0000-4000-8000-0000000000a1',
        attemptNo: 1,
        engine: 'chromium',
        suiteKey: 'checkout-smoke',
        caseKey: 'cart-total',
        status: 'FAILED',
        errorMessage: 'Error: expect(locator).toHaveText(expected) failed\nReceived: "$82.99"',
        occurredAt: '2026-11-24T11:00:00Z',
        artifacts: [
          {
            id: '00000000-0000-4000-8000-0000000000f1',
            attemptId: '00000000-0000-4000-8000-0000000000a1',
            caseKey: 'cart-total',
            kind: 'SCREENSHOT',
            mime: 'image/png',
            byteSize: 1500,
            sha256: 'a'.repeat(64),
            state: 'AVAILABLE',
            expiresAt: '2026-12-01T11:00:00Z',
            createdAt: '2026-11-24T11:00:00Z',
          },
          {
            id: '00000000-0000-4000-8000-0000000000f2',
            attemptId: '00000000-0000-4000-8000-0000000000a1',
            caseKey: 'cart-total',
            kind: 'TRACE',
            mime: 'application/zip',
            byteSize: 800,
            sha256: 'b'.repeat(64),
            state: 'PURGED',
            expiresAt: '2026-11-24T12:00:00Z',
            createdAt: '2026-11-24T11:00:00Z',
          },
        ],
      },
      {
        runId: RUN_ID,
        targetId: '00000000-0000-4000-8000-000000000002',
        attemptId: '00000000-0000-4000-8000-0000000000a2',
        attemptNo: 2,
        engine: 'firefox',
        suiteKey: 'checkout-smoke',
        caseKey: null,
        status: 'INFRA_ERROR',
        errorMessage: null,
        occurredAt: '2026-11-24T10:00:00Z',
        artifacts: [],
      },
    ],
    audit: [],
    ...overrides,
  };
}
