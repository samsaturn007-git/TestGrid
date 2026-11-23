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
  /** PRD-06 FR-7: ms after the attempt's first case started, from Playwright's own clock. */
  startOffsetMs: number | null;
}

export class ReportError extends Error {
  override name = 'ReportError';
}

const MAX_MESSAGE = 1000;

const ErrorBody = z.looseObject({ message: z.string().optional() });

// PRD-06 FR-1: screenshot: 'only-on-failure' and trace: 'retain-on-failure' attach files here.
const Attachment = z.looseObject({
  name: z.string(),
  contentType: z.string(),
  path: z.string().optional(),
});

const TestResult = z.looseObject({
  status: z.enum(['passed', 'failed', 'timedOut', 'skipped', 'interrupted']),
  duration: z.number().nonnegative(),
  retry: z.number().int().nonnegative(),
  error: ErrorBody.optional(),
  errors: z.array(ErrorBody).default([]),
  attachments: z.array(Attachment).default([]),
  startTime: z.string().optional(),
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

function caseResult(
  caseKey: string,
  test: z.infer<typeof ReportTest>,
): Omit<CaseResult, 'startOffsetMs'> {
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

/** PRD-06: the files Playwright kept for one non-passing case. Paths are not trusted yet. */
export interface CaseEvidence {
  caseKey: string;
  screenshot: string | null;
  trace: string | null;
}

/**
 * The try whose files explain the case: the last try of a failure, the first failed try of a
 * flake (the same try its error message comes from). Passing and skipped cases have none.
 */
function evidenceOf(caseKey: string, test: z.infer<typeof ReportTest>): CaseEvidence | null {
  if (test.status === 'expected' || test.status === 'skipped') return null;
  const failedTries = test.results.filter((r) => r.status !== 'passed' && r.status !== 'skipped');
  const chosen = test.status === 'flaky' ? failedTries[0] : test.results.at(-1);
  const file = (name: string, type: string) =>
    chosen?.attachments.find((a) => a.name === name && a.contentType === type && a.path)?.path ??
    null;
  const evidence = {
    caseKey,
    screenshot: file('screenshot', 'image/png'),
    trace: file('trace', 'application/zip'),
  };
  return evidence.screenshot || evidence.trace ? evidence : null;
}

export interface MappedResults {
  /** Non-passing cases with at least one kept file, in claim order. */
  evidence: CaseEvidence[];
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
  const found = new Map<string, Omit<CaseResult, 'startOffsetMs'>>();
  const started = new Map<string, number>();
  const files = new Map<string, CaseEvidence>();
  const visit = (node: SuiteNode): void => {
    for (const spec of node.specs ?? []) {
      const keys = spec.tags.map((t) => t.replace(/^@/, '')).filter((k) => claimed.has(k));
      for (const test of spec.tests) {
        if (test.projectName !== engine) continue;
        for (const key of keys) {
          if (found.has(key)) throw new ReportError(`case ${key} appears twice in the report`);
          found.set(key, caseResult(key, test));
          const first = Date.parse(test.results[0]?.startTime ?? '');
          if (Number.isFinite(first)) started.set(key, first);
          const evidence = evidenceOf(key, test);
          if (evidence) files.set(key, evidence);
        }
      }
    }
    for (const child of node.suites ?? []) visit(child);
  };
  for (const suite of report.suites) visit(suite);

  const missing = caseKeys.filter((k) => !found.has(k));
  // Offsets, not timestamps: they come from one clock (the Playwright process), so they line up
  // with each other even if the worker's clock is off; the API anchors them to the attempt.
  const origin = Math.min(...started.values());
  const offset = (k: string) => {
    const at = started.get(k);
    return at === undefined ? null : Math.round(at - origin);
  };
  const cases = caseKeys.map((k): CaseResult => ({
    ...(found.get(k) ?? {
      caseKey: k,
      status: 'SKIPPED' as const,
      durationMs: 0,
      retryCount: 0,
      errorClass: null,
      errorMessage: null,
    }),
    startOffsetMs: offset(k),
  }));
  const globalErrors = report.errors
    .map((e) => shortMessage(e.message))
    .filter((m): m is string => m !== null);
  const evidence = caseKeys.flatMap((k) => files.get(k) ?? []);
  return { cases, missing, globalErrors, evidence };
}
