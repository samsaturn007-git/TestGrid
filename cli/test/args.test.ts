import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseCommand } from '../src/args.ts';
import type { RunCommand } from '../src/args.ts';
import { CliError } from '../src/exit-codes.ts';

const RUN = [
  'run',
  '--project',
  'demo',
  '--suite',
  'checkout-smoke',
  '--engines',
  'chromium,firefox',
];

function usageError(argv: string[], env: Record<string, string> = {}): CliError {
  try {
    parseCommand(argv, env);
  } catch (error) {
    assert.ok(error instanceof CliError);
    return error;
  }
  assert.fail(`expected a usage error for ${argv.join(' ')}`);
}

describe('parseCommand', () => {
  it('parses run with defaults outside CI', () => {
    const cmd = parseCommand(RUN, {}) as RunCommand;
    assert.equal(cmd.name, 'run');
    assert.deepEqual(cmd.engines, ['chromium', 'firefox']);
    assert.equal(cmd.trigger, 'MANUAL');
    assert.equal(cmd.wait, false);
    assert.equal(cmd.timeoutMs, 60_000);
    assert.equal(cmd.apiUrl, 'http://127.0.0.1:8080/');
    assert.equal(cmd.ciRef, null);
    assert.equal(cmd.fixture, null);
  });

  it('takes the source and trigger from GitHub Actions', () => {
    const cmd = parseCommand([...RUN, '--wait'], {
      GITHUB_ACTIONS: 'true',
      GITHUB_REPOSITORY: 'owner/testgrid',
      GITHUB_SHA: '0123456789abcdef0123456789abcdef01234567',
      GITHUB_HEAD_REF: 'feature/x',
      GITHUB_REF_NAME: '12/merge',
      GITHUB_RUN_ID: '987654',
      GITHUB_RUN_ATTEMPT: '2',
    }) as RunCommand;
    assert.equal(cmd.trigger, 'CI');
    assert.equal(cmd.repo, 'owner/testgrid');
    assert.equal(cmd.branch, 'feature/x');
    assert.equal(cmd.ciRef, 'gh:987654/2');
    assert.equal(cmd.timeoutMs, 15 * 60_000);
  });

  it('accepts a fixture with a seed and a timeout', () => {
    const cmd = parseCommand(
      [...RUN, '--fixture', 'PRODUCT_DEFECT', '--seed', '7', '--wait', '--timeout', '90s'],
      {},
    ) as RunCommand;
    assert.deepEqual(cmd.fixture, { mode: 'PRODUCT_DEFECT', seed: 7 });
    assert.equal(cmd.timeoutMs, 90_000);
  });

  it('reads the token from TESTGRID_TOKEN only', () => {
    const cmd = parseCommand(['status', '11111111-1111-4111-8111-111111111111'], {
      TESTGRID_TOKEN: ' tg_ci_secret ',
    });
    assert.ok(cmd.name === 'status');
    assert.equal(cmd.token, 'tg_ci_secret');
    const error = usageError(['status', '11111111-1111-4111-8111-111111111111', '--token', 'x']);
    assert.match(error.message, /TESTGRID_TOKEN/);
  });

  it('ci004 refuses bad usage with exit 3', () => {
    for (const argv of [
      ['deploy'],
      [...RUN, '--engines', 'chromium,safari'],
      ['run', '--project', 'demo', '--suite', 'checkout-smoke', '--engines', 'chromium,chromium'],
      ['run', '--project', 'demo', '--engines', 'chromium'],
      [...RUN, '--fixture', 'EXPLODE'],
      [...RUN, '--seed', '4'],
      [...RUN, '--fixture', 'PASS', '--seed', '-1'],
      [...RUN, '--timeout', 'forever'],
      [...RUN, '--timeout', '7h'],
      [...RUN, '--trigger', 'RERUN'],
      [...RUN, '--suite', '../etc'],
      [...RUN, '--colour'],
      [...RUN, '--base', 'last-green'],
      [...RUN, '--api-url', 'file:///etc/passwd'],
      [...RUN, '--api-url', 'http://user:pw@api.example'],
      ['status'],
      ['status', 'not-a-uuid'],
      ['gate', '11111111-1111-4111-8111-111111111111', '--suite', 'x'],
      ['compare', '--head', '11111111-1111-4111-8111-111111111111'],
      ['compare', '--base', 'yesterday', '--head', '11111111-1111-4111-8111-111111111111'],
    ]) {
      assert.equal(usageError(argv).exitCode, 3, argv.join(' '));
    }
  });

  it('parses compare against last green', () => {
    const cmd = parseCommand(
      ['compare', '--base', 'last-green', '--head', '11111111-1111-4111-8111-111111111111'],
      {},
    );
    assert.ok(cmd.name === 'compare');
    assert.equal(cmd.base, 'last-green');
  });
});
