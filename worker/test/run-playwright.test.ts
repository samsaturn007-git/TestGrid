import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { childEnv, runPlaywright } from '../src/run-playwright.ts';

const FAKE = path.join(import.meta.dirname, 'fixtures', 'fake-cli', 'stubborn.mjs');

// "Alive" means running: a SIGKILLed orphan can linger for a moment as a zombie (state Z)
// until init/launchd reaps it, and kill(pid, 0) still succeeds on a zombie.
function alive(pid: number): boolean {
  try {
    const stat = execFileSync('ps', ['-o', 'stat=', '-p', String(pid)], { encoding: 'utf8' });
    return stat.trim() !== '' && !stat.trim().startsWith('Z');
  } catch {
    return false; // ps exits 1 when the pid doesn't exist
  }
}

function setup() {
  const dir = mkdtempSync(path.join(tmpdir(), 'tg-run-'));
  return { dir, pidFile: path.join(dir, 'pid'), envFile: path.join(dir, 'env.json') };
}

describe('runPlaywright process-group handling (FR-6)', () => {
  it('a timeout sends SIGTERM to the group, then SIGKILL after the grace period', async () => {
    const { dir, pidFile, envFile } = setup();
    const outcome = await runPlaywright({
      args: ['ignore-term', pidFile, envFile],
      env: childEnv(process.env, {}),
      cwd: dir,
      timeoutMs: 300,
      killGraceMs: 300,
      cliPath: FAKE,
    });
    assert.equal(outcome.stoppedBy, 'timeout');
    assert.equal(outcome.killed, true);
    assert.equal(outcome.exitSignal, 'SIGKILL');
    const grandchild = Number(readFileSync(pidFile, 'utf8'));
    assert.equal(alive(grandchild), false, 'the browser-like grandchild is gone too');
  });

  it('an abort reports its reason and leaves no process behind', async () => {
    const { dir, pidFile, envFile } = setup();
    const controller = new AbortController();
    setTimeout(() => controller.abort('shutdown'), 200);
    const outcome = await runPlaywright({
      args: ['plain', pidFile, envFile],
      env: childEnv(process.env, {}),
      cwd: dir,
      timeoutMs: 60_000,
      killGraceMs: 300,
      signal: controller.signal,
      cliPath: FAKE,
    });
    assert.equal(outcome.stoppedBy, 'shutdown');
    assert.equal(alive(Number(readFileSync(pidFile, 'utf8'))), false);
  });

  it('when the CLI exits on its own, stragglers in its group are swept', async () => {
    const { dir, pidFile, envFile } = setup();
    const outcome = await runPlaywright({
      args: ['exit-leaving-orphan', pidFile, envFile],
      env: childEnv(process.env, {}),
      cwd: dir,
      timeoutMs: 60_000,
      cliPath: FAKE,
    });
    assert.equal(outcome.exitCode, 3);
    assert.equal(outcome.stoppedBy, null);
    assert.equal(outcome.killed, true);
    const grandchild = Number(readFileSync(pidFile, 'utf8'));
    assert.equal(alive(grandchild), false);
  });

  it('the child gets only allowlisted variables, never the service token', async () => {
    const { dir, pidFile, envFile } = setup();
    const parent = {
      ...process.env,
      TESTGRID_WORKER_TOKEN: 'secret-token-that-must-not-leak-0123456789',
      AWS_SECRET_ACCESS_KEY: 'nope',
    };
    await runPlaywright({
      args: ['exit-leaving-orphan', pidFile, envFile],
      env: childEnv(parent, { TG_FIXTURE_MODE: 'PASS' }),
      cwd: dir,
      timeoutMs: 60_000,
      cliPath: FAKE,
    });
    const seen = JSON.parse(readFileSync(envFile, 'utf8')) as Record<string, string>;
    assert.equal(seen.TG_FIXTURE_MODE, 'PASS');
    assert.equal(seen.TESTGRID_WORKER_TOKEN, undefined);
    assert.equal(seen.AWS_SECRET_ACCESS_KEY, undefined);
    assert.equal(seen.FORCE_COLOR, '0');
  });
});
