import { z } from 'zod';
import { classifyFailure, stripAnsi } from './classify.ts';
import type { ErrorClass } from './classify.ts';

// FR-5: Playwright's JSON reporter output (pinned @playwright/test 1.65.1), parsed with zod
// and mapped to contracts §7 case results by tag. Only the fields used are checked; the
// reporter may add others. Recorded samples live in test/fixtures/playwright-1.65.1/.

export type CaseStatus = 'PASSED' | 'FAILED' | 'TIMED_OUT' | 'SKIPPED' | 'FLAKY';

export interface CaseResult {
  caseKey: string;
  status: CaseStatus;
  durationMs: number;
  retryCount: number;
  errorClass: ErrorClass | null;
  errorMessage: string | null;
}

export class ReportError extends Error {
  override name = 'ReportError';
}

const MAX_MESSAGE = 1000;

const ErrorBody = z.looseObject({ message: z.string().optional() });

const TestResult = z.looseObject({
  status: z.enum(['passed', 'failed', 'timedOut', 'skipped', 'interrupted']),
  duration: z.number().nonnegative(),
  retry: z.number().int().nonnegative(),
  error: ErrorBody.optional(),
  errors: z.array(ErrorBody).default([]),
});

const ReportTest = z.looseObject({
  projectName: z.string(),
  status: z.enum(['expected', 'unexpected', 'flaky', 'skipped']),
  results: z.array(TestResult),
});

const Spec = z.looseObject({
  title: z.string(),
  file: z.string(),
  tags: z.array(z.string()),
  tests: z.array(ReportTest),
});

interface SuiteNode {
  specs?: z.infer<typeof Spec>[] | undefined;
  suites?: SuiteNode[] | undefined;
}

const Suite: z.ZodType<SuiteNode> = z.lazy(() =>
  z.looseObject({
    specs: z.array(Spec).optional(),
    suites: z.array(Suite).optional(),
  }),
);

const Report = z.looseObject({
  suites: z.array(Suite),
  errors: z.array(ErrorBody).default([]),
  stats: z.looseObject({
    expected: z.number().int(),
    unexpected: z.number().int(),
    flaky: z.number().int(),
    skipped: z.number().int(),
  }),
});

export type PlaywrightReport = z.infer<typeof Report>;

/** Throws ReportError for truncated JSON or a shape that isn't a Playwright JSON report. */
export function parseReport(text: string): PlaywrightReport {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new ReportError('the Playwright report is not valid JSON (truncated or empty)');
  }
  const parsed = Report.safeParse(json);
  if (!parsed.success) {
    const first = parsed.error.issues[0];
    throw new ReportError(
      `the Playwright report has an unexpected shape at ${first?.path.join('.') ?? '?'}`,
    );
  }
  return parsed.data;
}

function shortMessage(raw: string | undefined): string | null {
  if (!raw) return null;
  const text = stripAnsi(raw).trim();
  return text.length <= MAX_MESSAGE ? text : `${text.slice(0, MAX_MESSAGE - 1)}…`;
}

function caseResult(caseKey: string, test: z.infer<typeof ReportTest>): CaseResult {
  const results = test.results;
  const durationMs = Math.round(results.reduce((sum, r) => sum + r.duration, 0));
  const retryCount = Math.max(0, results.length - 1);
  const firstFailure = results.find((r) => r.status !== 'passed' && r.status !== 'skipped');
  const last = results.at(-1);
  const failureOf = (r: z.infer<typeof TestResult> | undefined) => {
    const raw = r?.error?.message ?? r?.errors[0]?.message ?? '';
    return {
      errorClass: r?.status === 'interrupted' ? 'UNKNOWN' : classifyFailure(raw, r?.status ?? ''),
      errorMessage: shortMessage(raw) ?? (r?.status === 'interrupted' ? 'interrupted' : null),
    } as const;
  };

  if (test.status === 'skipped' || results.length === 0) {
    return {
      caseKey,
      status: 'SKIPPED',
      durationMs,
      retryCount: 0,
      errorClass: null,
      errorMessage: null,
    };
  }
  if (test.status === 'expected') {
    return {
      caseKey,
      status: 'PASSED',
      durationMs,
      retryCount,
      errorClass: null,
      errorMessage: null,
    };
  }
  if (test.status === 'flaky') {
    // Passed on a retry: keep the first failure as evidence of the flake.
    return { caseKey, status: 'FLAKY', durationMs, retryCount, ...failureOf(firstFailure) };
  }
  const status: CaseStatus = last?.status === 'timedOut' ? 'TIMED_OUT' : 'FAILED';
  return { caseKey, status, durationMs, retryCount, ...failureOf(last) };
}

export interface MappedResults {
  cases: CaseResult[];
  /** Claimed keys the report never mentioned (reported as SKIPPED). */
  missing: string[];
  /** Report-level errors, e.g. a config or global setup failure. */
  globalErrors: string[];
}

/**
 * One result per claimed case, in claim order. A report test is matched by its tag (the
 * reporter lists tags without "@") and by project = engine; tests for other projects or
 * unclaimed tags are ignored. Two report tests for one case is a broken report. O(t + c).
 */
export function mapReport(
  report: PlaywrightReport,
  engine: string,
  caseKeys: readonly string[],
): MappedResults {
  const claimed = new Set(caseKeys);
  const found = new Map<string, CaseResult>();
  const visit = (node: SuiteNode): void => {
    for (const spec of node.specs ?? []) {
      const keys = spec.tags.map((t) => t.replace(/^@/, '')).filter((k) => claimed.has(k));
      for (const test of spec.tests) {
        if (test.projectName !== engine) continue;
        for (const key of keys) {
          if (found.has(key)) throw new ReportError(`case ${key} appears twice in the report`);
          found.set(key, caseResult(key, test));
        }
      }
    }
    for (const child of node.suites ?? []) visit(child);
  };
  for (const suite of report.suites) visit(suite);

  const missing = caseKeys.filter((k) => !found.has(k));
  const cases = caseKeys.map(
    (k) =>
      found.get(k) ?? {
        caseKey: k,
        status: 'SKIPPED' as const,
        durationMs: 0,
        retryCount: 0,
        errorClass: null,
        errorMessage: null,
      },
  );
  const globalErrors = report.errors
    .map((e) => shortMessage(e.message))
    .filter((m): m is string => m !== null);
  return { cases, missing, globalErrors };
}
