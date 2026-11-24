import { parseArgs } from 'node:util';
import { parseDuration } from './backoff.ts';
import { CliError, EXIT } from './exit-codes.ts';

export const ENGINES = ['chromium', 'firefox', 'webkit'] as const;
export type Engine = (typeof ENGINES)[number];

const FIXTURE_MODES = [
  'PASS',
  'PRODUCT_DEFECT',
  'AUTOMATION_FLAKE',
  'SELECTOR_DRIFT',
  'TIMEOUT',
] as const;
export type FixtureMode = (typeof FIXTURE_MODES)[number];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEY = /^[a-z0-9]+(-[a-z0-9]+)*$/;

export const DEFAULT_WAIT_TIMEOUT_MS = 15 * 60_000;
export const DEFAULT_TIMEOUT_MS = 60_000;

export interface Common {
  apiUrl: string;
  token: string | null;
  json: boolean;
  wait: boolean;
  timeoutMs: number;
  summaryFile: string | null;
}

export interface RunCommand extends Common {
  name: 'run';
  project: string;
  suite: string;
  engines: Engine[];
  fixture: { mode: FixtureMode; seed: number } | null;
  trigger: 'CI' | 'MANUAL';
  repo: string | null;
  commitSha: string | null;
  branch: string | null;
  ciRef: string | null;
  idempotencyKey: string | null;
}

export interface RunIdCommand extends Common {
  name: 'status' | 'gate';
  runId: string;
}

export interface CompareCommand extends Common {
  name: 'compare';
  base: string;
  head: string;
}

export type Command = RunCommand | RunIdCommand | CompareCommand | { name: 'help' };

export const USAGE = `usage:
  testgrid run --project demo --suite checkout-smoke --engines chromium,firefox
               [--fixture PASS [--seed 42]] [--wait] [--timeout 15m] [--json]
               [--trigger CI|MANUAL] [--repo owner/name] [--commit <sha>] [--branch <name>]
               [--ci-ref <system>:<id>] [--idempotency-key <key>] [--summary-file <path>]
  testgrid status <runId> [--timeout 60s] [--json]
  testgrid gate <runId> [--wait] [--timeout 15m] [--json] [--summary-file <path>]
  testgrid compare --base last-green|<runId> --head <runId> [--timeout 60s] [--json]

common: --api-url <url> (or TESTGRID_API_URL, default http://127.0.0.1:8080)
        --summary-file appends a markdown report (run --wait, gate), e.g. $GITHUB_STEP_SUMMARY
        --timeout bounds the whole command, retries included (default 15m with --wait, else 60s)
token:  TESTGRID_TOKEN only; it is never accepted as an argument
exit:   0 PASS, 1 FAIL, 2 INDETERMINATE (infra, unfinished, deadline), 3 usage, config or auth
`;

const OPTIONS = {
  'api-url': { type: 'string' },
  json: { type: 'boolean' },
  wait: { type: 'boolean' },
  timeout: { type: 'string' },
  help: { type: 'boolean', short: 'h' },
  project: { type: 'string' },
  suite: { type: 'string' },
  engines: { type: 'string' },
  fixture: { type: 'string' },
  seed: { type: 'string' },
  trigger: { type: 'string' },
  repo: { type: 'string' },
  commit: { type: 'string' },
  branch: { type: 'string' },
  'ci-ref': { type: 'string' },
  'idempotency-key': { type: 'string' },
  'summary-file': { type: 'string' },
  base: { type: 'string' },
  head: { type: 'string' },
  token: { type: 'string' },
} as const;

type Values = ReturnType<
  typeof parseArgs<{ options: typeof OPTIONS; allowPositionals: true }>
>['values'];

const ALLOWED: Record<string, readonly (keyof typeof OPTIONS)[]> = {
  run: [
    'project',
    'suite',
    'engines',
    'fixture',
    'seed',
    'trigger',
    'repo',
    'commit',
    'branch',
    'ci-ref',
    'idempotency-key',
    'wait',
    'timeout',
    'summary-file',
  ],
  status: ['timeout'],
  gate: ['wait', 'timeout', 'summary-file'],
  compare: ['base', 'head', 'timeout'],
};

function usage(message: string): never {
  throw new CliError(EXIT.USAGE, `${message}\n\n${USAGE}`);
}

/** Parses argv strictly: unknown flags, a flag the command doesn't take and bad values are exit 3. */
export function parseCommand(
  argv: readonly string[],
  env: Readonly<Record<string, string | undefined>>,
): Command {
  let parsed;
  try {
    parsed = parseArgs({ args: [...argv], options: OPTIONS, allowPositionals: true, strict: true });
  } catch (error) {
    usage(error instanceof Error ? error.message : String(error));
  }
  const { values, positionals } = parsed;
  if (values.help || positionals.length === 0) {
    return { name: 'help' };
  }
  if (values.token !== undefined) {
    usage(
      '--token is refused so the token never shows in a process list or shell history; set TESTGRID_TOKEN',
    );
  }
  const [name, ...rest] = positionals;
  const allowed = name === undefined ? undefined : ALLOWED[name];
  if (name === undefined || allowed === undefined) {
    usage(`unknown command ${JSON.stringify(name)}`);
  }
  const common = ['api-url', 'json', 'help'];
  for (const flag of Object.keys(values)) {
    if (!common.includes(flag) && !allowed.includes(flag as keyof typeof OPTIONS)) {
      usage(`${name} does not take --${flag}`);
    }
  }
  const base = commonOptions(values, env);
  switch (name) {
    case 'run':
      if (rest.length > 0) {
        usage(`run takes no positional arguments (got ${rest.join(' ')})`);
      }
      return runCommand(values, env, base);
    case 'status':
    case 'gate':
      return { name, runId: runIdArg(rest, name), ...base };
    default:
      if (rest.length > 0) {
        usage('compare takes --base and --head, not positional arguments');
      }
      return {
        name: 'compare',
        base: values.base === 'last-green' ? 'last-green' : uuidValue('--base', values.base),
        head: uuidValue('--head', values.head),
        ...base,
      };
  }
}

function commonOptions(values: Values, env: Readonly<Record<string, string | undefined>>): Common {
  const apiUrl = values['api-url'] ?? env.TESTGRID_API_URL ?? 'http://127.0.0.1:8080';
  let url: URL;
  try {
    url = new URL(apiUrl);
  } catch {
    usage(`--api-url ${JSON.stringify(apiUrl)} is not a URL`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    usage('--api-url must be http or https');
  }
  if (url.username || url.password || url.search || url.hash) {
    usage('--api-url must not carry credentials, a query or a fragment');
  }
  const wait = values.wait ?? false;
  let timeoutMs = wait ? DEFAULT_WAIT_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
  if (values.timeout !== undefined) {
    const parsed = parseDuration(values.timeout);
    if (parsed === null) {
      usage('--timeout must be like 90s, 15m or 1h (1 s … 6 h)');
    }
    timeoutMs = parsed;
  }
  const token = env.TESTGRID_TOKEN?.trim() ? env.TESTGRID_TOKEN.trim() : null;
  const summaryFile = values['summary-file'] ?? null;
  if (summaryFile !== null && summaryFile.trim() === '') {
    usage('--summary-file needs a path');
  }
  return {
    apiUrl: url.toString(),
    token,
    json: values.json ?? false,
    wait,
    timeoutMs,
    summaryFile,
  };
}

function runCommand(
  values: Values,
  env: Readonly<Record<string, string | undefined>>,
  base: Common,
): RunCommand {
  const project = required('--project', values.project);
  const suite = required('--suite', values.suite);
  if (!KEY.test(suite)) {
    usage('--suite must be a manifest suite key such as checkout-smoke');
  }
  const engines = required('--engines', values.engines)
    .split(',')
    .map((e) => e.trim())
    .filter(Boolean);
  if (engines.length === 0 || engines.length > 3 || new Set(engines).size !== engines.length) {
    usage('--engines must list 1-3 distinct engines, comma-separated');
  }
  for (const engine of engines) {
    if (!(ENGINES as readonly string[]).includes(engine)) {
      usage(`--engines: ${engine} is not one of ${ENGINES.join(', ')}`);
    }
  }
  let fixture: RunCommand['fixture'] = null;
  if (values.fixture !== undefined) {
    const mode = FIXTURE_MODES.find((m) => m === values.fixture);
    if (mode === undefined) {
      usage(`--fixture must be one of ${FIXTURE_MODES.join(', ')}`);
    }
    const seed = values.seed === undefined ? 42 : Number(values.seed);
    if (!/^[0-9]{1,10}$/.test(values.seed ?? '42') || seed > 2 ** 31 - 1) {
      usage('--seed must be 0 … 2147483647');
    }
    fixture = { mode, seed };
  } else if (values.seed !== undefined) {
    usage('--seed needs --fixture');
  }
  const inCi = env.GITHUB_ACTIONS === 'true' || env.CI === 'true';
  const trigger = values.trigger ?? (inCi ? 'CI' : 'MANUAL');
  if (trigger !== 'CI' && trigger !== 'MANUAL') {
    usage('--trigger must be CI or MANUAL');
  }
  const githubRef =
    env.GITHUB_RUN_ID && /^[0-9]+$/.test(env.GITHUB_RUN_ID)
      ? `gh:${env.GITHUB_RUN_ID}/${/^[0-9]+$/.test(env.GITHUB_RUN_ATTEMPT ?? '') ? env.GITHUB_RUN_ATTEMPT : '1'}`
      : null;
  return {
    name: 'run',
    project,
    suite,
    engines: engines as Engine[],
    fixture,
    trigger,
    repo: values.repo ?? env.GITHUB_REPOSITORY ?? null,
    commitSha: values.commit ?? env.GITHUB_SHA ?? null,
    branch: values.branch ?? (env.GITHUB_HEAD_REF || env.GITHUB_REF_NAME || null),
    ciRef: values['ci-ref'] ?? githubRef,
    idempotencyKey: values['idempotency-key'] ?? null,
    ...base,
  };
}

function required(flag: string, value: string | undefined): string {
  if (value === undefined || value.trim() === '') {
    usage(`${flag} is required`);
  }
  return value.trim();
}

function uuidValue(flag: string, value: string | undefined): string {
  const v = required(flag, value).toLowerCase();
  if (!UUID.test(v)) {
    usage(`${flag} must be a run ID (UUID)`);
  }
  return v;
}

function runIdArg(rest: string[], name: string): string {
  if (rest.length !== 1) {
    usage(`${name} takes exactly one run ID`);
  }
  return uuidValue(`${name} <runId>`, rest[0]);
}
