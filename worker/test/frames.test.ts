import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { framesOf } from '../src/frames.ts';

const ROOT = '/app/worker';

describe('framesOf (PRD-07 FR-1, CONFLICT_REGISTER N24)', () => {
  it('keeps our own frames as file:function, innermost first, without line numbers', () => {
    const stack = [
      'Error: expect(locator).toHaveText(expected) failed',
      '    at addItem (/app/worker/suites/support/cart.ts:12:3)',
      '    at async Object.checkout [as run] (/app/worker/suites/checkout-smoke.spec.ts:53:48)',
      '    at /app/worker/suites/checkout-smoke.spec.ts:20:5',
    ].join('\n');
    assert.deepEqual(framesOf(stack, ROOT), [
      'suites/support/cart.ts:addItem',
      'suites/checkout-smoke.spec.ts:Object.checkout',
      'suites/checkout-smoke.spec.ts:<anonymous>',
    ]);
  });

  it('skips Playwright, Node internals and files outside the worker root', () => {
    const stack = [
      'TimeoutError: locator.click: Timeout 5000ms exceeded.',
      '    at Proxy.click (/app/worker/node_modules/playwright-core/lib/client/locator.js:1:2)',
      '    at process.processTicksAndRejections (node:internal/process/task_queues:105:5)',
      '    at run (/usr/lib/node_modules/x/index.js:3:4)',
      '    at /app/workerish/suites/a.spec.ts:3:4',
      '    at /app/worker/suites/a.spec.ts:7:9',
    ].join('\n');
    assert.deepEqual(framesOf(stack, ROOT), ['suites/a.spec.ts:<anonymous>']);
  });

  it('accepts file:// URLs and keeps at most three frames', () => {
    const lines = ['Error: x'];
    for (let i = 0; i < 5; i++) lines.push(`    at f${i} (file:///app/worker/suites/s${i}.ts:1:1)`);
    assert.deepEqual(framesOf(lines.join('\n'), ROOT), [
      'suites/s0.ts:f0',
      'suites/s1.ts:f1',
      'suites/s2.ts:f2',
    ]);
  });

  it('turns function names that are not identifiers into <anonymous>', () => {
    const stack = 'Error: x\n    at weird name! (/app/worker/suites/a.spec.ts:1:1)';
    assert.deepEqual(framesOf(stack, ROOT), ['suites/a.spec.ts:<anonymous>']);
  });

  it('returns no frames without a stack or without "at" lines', () => {
    assert.deepEqual(framesOf(undefined, ROOT), []);
    assert.deepEqual(framesOf('Test timeout of 20000ms exceeded.', ROOT), []);
    assert.deepEqual(framesOf('    at async Promise.all (index 0)', ROOT), []);
  });
});
