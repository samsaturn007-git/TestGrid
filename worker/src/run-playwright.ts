import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { StringDecoder } from 'node:string_decoder';

// FR-5/FR-6: runs the Playwright CLI as a child process. shell: false and a typed argv mean
// no string is ever interpreted by a shell. detached: true puts the child in its own process
// group, so the browsers it starts can be signalled together and none outlive the attempt.

export const PLAYWRIGHT_CLI = createRequire(import.meta.url).resolve('@playwright/test/cli');
export const KILL_GRACE_MS = 10_000;
const TAIL_BYTES = 16 * 1024;
// PRD-06 FR-1: the log artifact keeps 256 KB. Twice that is captured, so redaction runs on more
// than is kept and a secret split at the capture edge falls outside the kept tail.
export const LOG_CAPTURE_CHARS = 512 * 1024;

// Only these variables reach Playwright. The worker's own secrets (the service token)
// are not in the list, so a test or a browser can never read them.
const INHERITED_ENV = ['PATH', 'HOME', 'TMPDIR', 'LANG', 'TZ', 'PLAYWRIGHT_BROWSERS_PATH'];

export function childEnv(
  parent: NodeJS.ProcessEnv,
  extra: Record<string, string>,
): Record<string, string> {
  const env: Record<string, string> = { FORCE_COLOR: '0', NO_COLOR: '1' };
  for (const key of INHERITED_ENV) {
    const value = parent[key];
    if (value !== undefined) env[key] = value;
  }
  return { ...env, ...extra };
}

export type StopReason = 'timeout' | 'cancelled' | 'shutdown' | 'lease-lost';

export interface SpawnSpec {
  args: readonly string[];
  env: Record<string, string>;
  cwd: string;
  timeoutMs: number;
  killGraceMs?: number;
  /** Aborting stops the run; the abort reason (a StopReason) is reported back. */
  signal?: AbortSignal;
  cliPath?: string;
}

export interface RunOutcome {
  exitCode: number | null;
  exitSignal: NodeJS.Signals | null;
  stoppedBy: StopReason | null;
  /** True if the process group had to be killed with SIGKILL after the grace period. */
  killed: boolean;
  stderrTail: string;
  /** stdout and stderr interleaved as they arrived, the last LOG_CAPTURE_CHARS characters. */
  logTail: string;
  durationMs: number;
}

function signalGroup(pid: number, signal: NodeJS.Signals): boolean {
  try {
    process.kill(-pid, signal);
    return true;
  } catch {
    return false; // ESRCH: the whole group is already gone.
  }
}

export function groupAlive(pid: number): boolean {
  return signalGroup(pid, 0 as unknown as NodeJS.Signals);
}

/**
 * SIGKILL is delivered asynchronously, and a killed process exists until it is reaped. Report
 * the attempt as finished only once the group is really gone (bounded, with a final SIGKILL).
 */
async function waitForGroupExit(pid: number, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (groupAlive(pid) && Date.now() < deadline) {
    signalGroup(pid, 'SIGKILL');
    await new Promise((r) => setTimeout(r, 25));
  }
}

export function runPlaywright(spec: SpawnSpec): Promise<RunOutcome> {
  const started = Date.now();
  const grace = spec.killGraceMs ?? KILL_GRACE_MS;
  const child = spawn(process.execPath, [spec.cliPath ?? PLAYWRIGHT_CLI, ...spec.args], {
    cwd: spec.cwd,
    env: spec.env,
    shell: false,
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const pid = child.pid;

  let stderrTail = '';
  let logTail = '';
  // A decoder per stream, so a multi-byte character split across chunks stays intact.
  const outText = new StringDecoder('utf8');
  const errText = new StringDecoder('utf8');
  const keep = (text: string) => {
    logTail = (logTail + text).slice(-LOG_CAPTURE_CHARS);
  };
  child.stderr.on('data', (chunk: Buffer) => {
    const text = errText.write(chunk);
    stderrTail = (stderrTail + text).slice(-TAIL_BYTES);
    keep(text);
  });
  // The JSON report goes to a file; stdout (the list reporter) only feeds the log tail.
  child.stdout.on('data', (chunk: Buffer) => keep(outText.write(chunk)));

  return new Promise((resolve) => {
    let stoppedBy: StopReason | null = null;
    let killed = false;
    let killTimer: NodeJS.Timeout | undefined;

    const stop = (reason: StopReason) => {
      if (stoppedBy || pid === undefined) return;
      stoppedBy = reason;
      signalGroup(pid, 'SIGTERM');
      killTimer = setTimeout(() => {
        if (groupAlive(pid)) {
          killed = true;
          signalGroup(pid, 'SIGKILL');
        }
      }, grace);
    };

    const timeout = setTimeout(() => stop('timeout'), spec.timeoutMs);
    const onAbort = () => stop((spec.signal?.reason as StopReason | undefined) ?? 'cancelled');
    if (spec.signal?.aborted) onAbort();
    spec.signal?.addEventListener('abort', onAbort, { once: true });

    // The CLI may exit while something it started is still alive (and maybe holding our
    // pipes open): sweep the group as soon as the leader exits, so nothing survives the
    // attempt and 'close' can follow.
    child.on('exit', () => {
      if (pid !== undefined && groupAlive(pid)) {
        killed = true;
        signalGroup(pid, 'SIGKILL');
      }
    });

    const finish = async (exitCode: number | null, exitSignal: NodeJS.Signals | null) => {
      clearTimeout(timeout);
      clearTimeout(killTimer);
      spec.signal?.removeEventListener('abort', onAbort);
      if (pid !== undefined) await waitForGroupExit(pid);
      resolve({
        exitCode,
        exitSignal,
        stoppedBy,
        killed,
        stderrTail,
        logTail: logTail + outText.end() + errText.end(),
        durationMs: Date.now() - started,
      });
    };
    child.on('error', (err) => {
      stderrTail += `\nspawn failed: ${err.message}`;
      void finish(null, null);
    });
    child.on('close', (code, sig) => void finish(code, sig));
  });
}
