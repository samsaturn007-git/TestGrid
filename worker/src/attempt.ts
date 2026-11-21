import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { Claim } from './claim.ts';
import type { ErrorClass } from './classify.ts';
import type { LoadedSuite } from './manifest.ts';
import { suiteByKey } from './manifest.ts';
import { buildPlaywrightArgs } from './playwright-args.ts';
import { redact } from './redact.ts';
import { mapReport, parseReport, ReportError } from './report.ts';
import type { CaseResult } from './report.ts';
import { childEnv, runPlaywright } from './run-playwright.ts';
import type { RunOutcome, StopReason } from './run-playwright.ts';

// Turns one claim into one completion payload (contracts §7). Every path ends in a payload:
// a crash, a timeout or an unreadable report becomes an honest non-PASSED status, never a
// silent success.

export type CompletionStatus = 'COMPLETED_WITH_RESULTS' | 'INFRA_ERROR' | 'TIMED_OUT' | 'CANCELLED';

export interface Completion {
  status: CompletionStatus;
  caseResults: CaseResult[];
  workerError: { errorClass: ErrorClass; message: string | null } | null;
}

export interface AttemptDeps {
  suites: readonly LoadedSuite[];
  workerRoot: string;
  configPath: string;
  /** Resolved value of the manifest's baseUrlRef (DEMO_SHOP_INTERNAL). */
  baseUrl: string;
  secrets: readonly string[];
  parentEnv: NodeJS.ProcessEnv;
  killGraceMs?: number;
  cliPath?: string;
  onSpawn?: (args: readonly string[]) => void;
}

function infra(errorClass: ErrorClass, message: string, secrets: readonly string[]): Completion {
  return {
    status: 'INFRA_ERROR',
    caseResults: [],
    workerError: { errorClass, message: redact(message, secrets) },
  };
}

const STOPPED: Record<StopReason, (timeoutMs: number) => Completion> = {
  timeout: (ms) => ({
    status: 'TIMED_OUT',
    caseResults: [],
    workerError: {
      errorClass: 'TEST_TIMEOUT',
      message: `attempt exceeded the suite timeout of ${ms} ms`,
    },
  }),
  cancelled: () => ({
    status: 'CANCELLED',
    caseResults: [],
    workerError: { errorClass: 'CANCELLED', message: 'cancel requested' },
  }),
  shutdown: () => ({
    status: 'INFRA_ERROR',
    caseResults: [],
    workerError: {
      errorClass: 'WORKER_SHUTDOWN',
      message: 'worker shut down before the attempt finished',
    },
  }),
  'lease-lost': () => ({
    status: 'INFRA_ERROR',
    caseResults: [],
    workerError: { errorClass: 'LEASE_EXPIRED', message: 'lease lost while running' },
  }),
};

export async function executeAttempt(
  claim: Claim,
  deps: AttemptDeps,
  signal?: AbortSignal,
): Promise<Completion> {
  let loaded: LoadedSuite;
  try {
    loaded = suiteByKey(deps.suites, claim.suite.suiteKey);
  } catch {
    return infra(
      'CONFIG',
      `suite ${claim.suite.suiteKey} is not in this worker's manifest`,
      deps.secrets,
    );
  }
  // The worker runs exactly the revision the API claimed, or nothing.
  if (loaded.revision !== claim.suite.revision) {
    return infra(
      'CONFIG',
      `worker has ${claim.suite.suiteKey} revision ${loaded.revision}, the claim is for ${claim.suite.revision}`,
      deps.secrets,
    );
  }

  const dir = mkdtempSync(path.join(tmpdir(), 'tg-attempt-'));
  try {
    const reportFile = path.join(dir, 'report.json');
    let args: string[];
    try {
      args = buildPlaywrightArgs({
        suite: loaded.suite,
        engine: claim.engine,
        caseKeys: claim.caseKeys,
        configPath: deps.configPath,
        outputDir: path.join(dir, 'output'),
      });
    } catch (e) {
      return infra('CONFIG', (e as Error).message, deps.secrets);
    }
    deps.onSpawn?.(args);
    const outcome = await runPlaywright({
      args,
      cwd: deps.workerRoot,
      env: childEnv(deps.parentEnv, {
        TG_BASE_URL: deps.baseUrl,
        // A run without a fixture still uses the shop's default session (PASS, seed 42).
        TG_FIXTURE_MODE: claim.fixture?.mode ?? 'PASS',
        TG_FIXTURE_SEED: String(claim.fixture?.seed ?? 42),
        PLAYWRIGHT_JSON_OUTPUT_FILE: reportFile,
      }),
      timeoutMs: claim.timeoutMs,
      ...(deps.killGraceMs === undefined ? {} : { killGraceMs: deps.killGraceMs }),
      ...(signal === undefined ? {} : { signal }),
      ...(deps.cliPath === undefined ? {} : { cliPath: deps.cliPath }),
    });
    if (outcome.stoppedBy) return STOPPED[outcome.stoppedBy](claim.timeoutMs);
    return fromReport(claim, outcome, reportFile, deps.secrets);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function fromReport(
  claim: Claim,
  outcome: RunOutcome,
  reportFile: string,
  secrets: readonly string[],
): Completion {
  let text: string;
  try {
    text = readFileSync(reportFile, 'utf8');
  } catch {
    // FR-7: a process crash without a report is UNKNOWN.
    return infra(
      'UNKNOWN',
      `Playwright exited (code ${outcome.exitCode}, signal ${outcome.exitSignal}) without a report: ${outcome.stderrTail.slice(-500)}`,
      secrets,
    );
  }
  let mapped;
  try {
    mapped = mapReport(parseReport(text), claim.engine, claim.caseKeys);
  } catch (e) {
    if (!(e instanceof ReportError)) throw e;
    return infra('UNKNOWN', `${e.message} (exit code ${outcome.exitCode})`, secrets);
  }
  const executed = mapped.cases.filter((c) => c.status !== 'SKIPPED');
  if (executed.length === 0) {
    const why = mapped.globalErrors[0] ?? `no claimed case ran (exit code ${outcome.exitCode})`;
    return infra('CONFIG', why, secrets);
  }
  const failed = executed.filter((c) => c.status === 'FAILED' || c.status === 'TIMED_OUT');
  // Every failure is the browser not starting: an infrastructure fault, not a test result.
  if (
    failed.length > 0 &&
    failed.every((c) => c.errorClass === 'BROWSER_LAUNCH') &&
    failed.length === executed.length
  ) {
    return infra('BROWSER_LAUNCH', failed[0]?.errorMessage ?? 'browser failed to launch', secrets);
  }
  return {
    status: 'COMPLETED_WITH_RESULTS',
    caseResults: mapped.cases.map((c) => ({ ...c, errorMessage: redact(c.errorMessage, secrets) })),
    workerError: null,
  };
}
