import type { components } from '../../packages/contracts/src/api.ts';
import type { Command, CompareCommand, RunCommand, RunIdCommand } from './args.ts';
import { backoffDelays } from './backoff.ts';
import { ApiClient } from './client.ts';
import type { Deps } from './client.ts';
import { CliError, EXIT, exitFor } from './exit-codes.ts';
import type { ExitCode } from './exit-codes.ts';
import { compareText, qualityText, statusText } from './format.ts';
import { idempotencyKey } from './idempotency.ts';

type Schemas = components['schemas'];
export type Run = Schemas['RunResponse'];
export type Quality = Schemas['RunQualityView'];
type Comparison = Schemas['RunComparisonView'];

const TERMINAL = new Set(['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** What a command prints: one JSON document with --json, else the text. */
export interface Result {
  exitCode: ExitCode;
  document: Record<string, unknown>;
  text: string;
  report?: GateReport;
}

export interface CaseLine {
  caseKey: string | null;
  engine: string | null;
  status: string | null;
  target: string | null;
  critical: boolean;
  detail: string;
}

export interface GateReport {
  command: 'run' | 'gate';
  runId: string;
  runStatus: Run['status'];
  suiteKey: string;
  outcome: Quality['outcome'];
  exitCode: ExitCode;
  reason: string;
  deadlineReached: boolean;
  policy: Quality['policy'];
  evaluatedAt: string | null;
  rules: Quality['rules'];
  failedCases: CaseLine[];
  quarantinedFailures: CaseLine[];
  links: { run: string; quality: string };
}

export async function execute(command: Command, deps: Deps): Promise<Result> {
  switch (command.name) {
    case 'help':
      throw new Error('help is handled by main');
    case 'run':
      return run(command, deps);
    case 'status':
      return status(command, deps);
    case 'gate':
      return gate(command, deps);
    case 'compare':
      return compare(command, deps);
  }
}

function client(command: RunCommand | RunIdCommand | CompareCommand, deps: Deps): ApiClient {
  return new ApiClient(command.apiUrl, command.token, deps, deps.now() + command.timeoutMs);
}

async function run(command: RunCommand, deps: Deps): Promise<Result> {
  const api = client(command, deps);
  const projectId = await resolveProject(api, command.project);
  const key =
    command.idempotencyKey ??
    idempotencyKey({
      repo: command.repo,
      commitSha: command.commitSha,
      ciRef: command.ciRef,
      suite: command.suite,
      engines: command.engines,
      fixture: command.fixture,
    });
  const source: Record<string, string> = {};
  if (command.commitSha) source.commitSha = command.commitSha;
  if (command.branch) source.branch = command.branch;
  if (command.ciRef) source.ciRef = command.ciRef;
  const request: Schemas['CreateRunRequest'] = {
    suiteKey: command.suite,
    targets: command.engines.map((engine) => ({ executor: 'LOCAL', engine })),
    trigger: command.trigger,
    idempotencyKey: key,
    ...(command.fixture ? { fixture: command.fixture } : {}),
    ...(Object.keys(source).length > 0 ? { source } : {}),
  };
  const created = await api.post<Run>(`/projects/${projectId}/runs`, request);
  const replayed = created.status === 200;
  deps.stderr(
    `testgrid: run ${created.body.id} ${replayed ? 'replayed (same idempotency key)' : 'created'}` +
      ` for ${command.suite} on ${command.engines.join(', ')}\n`,
  );
  if (!command.wait) {
    return {
      exitCode: EXIT.PASS,
      document: {
        command: 'run',
        runId: created.body.id,
        runStatus: created.body.status,
        replayed,
        idempotencyKey: key,
        outcome: null,
        exitCode: EXIT.PASS,
      },
      text: `${created.body.id}\n`,
    };
  }
  return report('run', api, command, created.body.id, deps, true);
}

async function resolveProject(api: ApiClient, project: string): Promise<string> {
  if (UUID.test(project)) {
    return project;
  }
  const projects = await api.get<Schemas['PageProject']>('/projects');
  const match = projects.body.items.find((p) => p.slug === project);
  if (!match) {
    throw new CliError(EXIT.USAGE, `project ${JSON.stringify(project)} not found`);
  }
  return match.id;
}

async function status(command: RunIdCommand, deps: Deps): Promise<Result> {
  const api = client(command, deps);
  const runResponse = await api.get<Run>(`/runs/${command.runId}`);
  return {
    exitCode: EXIT.PASS,
    document: { command: 'status', exitCode: EXIT.PASS, run: runResponse.body },
    text: statusText(runResponse.body),
  };
}

async function gate(command: RunIdCommand, deps: Deps): Promise<Result> {
  return report('gate', client(command, deps), command, command.runId, deps, command.wait);
}

async function compare(command: CompareCommand, deps: Deps): Promise<Result> {
  const api = client(command, deps);
  const query = new URLSearchParams({ base: command.base, head: command.head });
  const comparison = await api.get<Comparison>(`/runs/compare?${query.toString()}`);
  return {
    exitCode: EXIT.PASS,
    document: { command: 'compare', exitCode: EXIT.PASS, comparison: comparison.body },
    text: compareText(comparison.body),
  };
}

/**
 * Polls the run with the backoff until it is terminal and evaluated, or the deadline passes. A
 * deadline is exit 2 with the run's last status, never 0, whatever state the run was in.
 */
async function report(
  name: 'run' | 'gate',
  api: ApiClient,
  command: { apiUrl: string },
  runId: string,
  deps: Deps,
  wait: boolean,
): Promise<Result> {
  const delays = backoffDelays(deps.random);
  let last: Run;
  let quality: Quality | null = null;
  let deadlineReached = false;
  for (;;) {
    last = (await api.get<Run>(`/runs/${runId}`)).body;
    if (TERMINAL.has(last.status)) {
      const q = (await api.get<Quality>(`/runs/${runId}/quality`)).body;
      if (q.outcome !== null) {
        quality = q;
        break;
      }
    }
    if (!wait) {
      break;
    }
    const remaining = api.deadline - deps.now();
    if (remaining <= 0) {
      deadlineReached = true;
      break;
    }
    const delay = Math.min(delays.next().value as number, remaining);
    deps.stderr(
      `testgrid: run ${runId} is ${last.status}; next check in ${Math.round(delay / 100) / 10} s\n`,
    );
    await deps.sleep(delay);
    if (deps.now() >= api.deadline) {
      deadlineReached = true;
      break;
    }
  }
  const gateReport = buildReport(name, command.apiUrl, last, quality, deadlineReached);
  return {
    exitCode: gateReport.exitCode,
    document: gateReport as unknown as Record<string, unknown>,
    text: qualityText(gateReport),
    report: gateReport,
  };
}

export function buildReport(
  command: 'run' | 'gate',
  apiUrl: string,
  run: Run,
  quality: Quality | null,
  deadlineReached: boolean,
): GateReport {
  const base = apiUrl.replace(/\/+$/, '');
  const links = {
    run: `${base}/api/v1/runs/${run.id}`,
    quality: `${base}/api/v1/runs/${run.id}/quality`,
  };
  if (!quality || quality.outcome === null) {
    return {
      command,
      runId: run.id,
      runStatus: run.status,
      suiteKey: run.suiteKey,
      outcome: 'INDETERMINATE',
      exitCode: EXIT.INDETERMINATE,
      reason: deadlineReached
        ? `the run did not finish and get evaluated before the deadline (last status ${run.status})`
        : `the run is ${run.status} and has no evaluation yet (use --wait)`,
      deadlineReached,
      policy: null,
      evaluatedAt: null,
      rules: [],
      failedCases: [],
      quarantinedFailures: [],
      links,
    };
  }
  const rules = quality.rules;
  const critical = new Set(
    evidenceOf(rules, 'critical-cases').map((e) => `${e.caseKey ?? ''}|${e.engine ?? ''}`),
  );
  const line = (e: Schemas['QualityRuleEvidence']): CaseLine => ({
    caseKey: e.caseKey,
    engine: e.engine,
    status: e.status,
    target: e.target,
    critical: critical.has(`${e.caseKey ?? ''}|${e.engine ?? ''}`),
    detail: e.detail,
  });
  const failing = (outcome: string) =>
    rules.filter((r) => r.blocking && r.outcome === outcome).map((r) => r.ruleId);
  const reason =
    quality.outcome === 'PASS'
      ? 'every blocking rule passed'
      : quality.outcome === 'FAIL'
        ? `blocking rules failed: ${failing('FAIL').join(', ')}`
        : `blocking rules were inconclusive: ${failing('INDETERMINATE').join(', ')}`;
  return {
    command,
    runId: run.id,
    runStatus: run.status,
    suiteKey: run.suiteKey,
    outcome: quality.outcome,
    exitCode: exitFor(quality.outcome),
    reason,
    deadlineReached: false,
    policy: quality.policy,
    evaluatedAt: quality.evaluatedAt,
    rules,
    failedCases: evidenceOf(rules, 'max-failed-cases')
      .filter((e) => e.counted)
      .map(line),
    quarantinedFailures: evidenceOf(rules, 'quarantine-honored').map(line),
    links,
  };
}

function evidenceOf(rules: Quality['rules'], ruleId: string): Schemas['QualityRuleEvidence'][] {
  return rules.find((r) => r.ruleId === ruleId)?.evidence ?? [];
}
