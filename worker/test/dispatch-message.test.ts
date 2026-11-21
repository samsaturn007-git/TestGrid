import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseDispatchMessage, retryDelayMs, traceIdOf } from '../src/dispatch-message.ts';

describe('dispatch message', () => {
  const valid = {
    messageId: '00000000-0000-4000-8000-0000000000d1',
    targetId: '00000000-0000-4000-8000-0000000000c1',
    dispatchGeneration: 1,
    schemaVersion: 1,
  };

  it('parses exactly the contracts §9 body', () => {
    assert.deepEqual(parseDispatchMessage(Buffer.from(JSON.stringify(valid))), valid);
    assert.equal(parseDispatchMessage(Buffer.from('[]')), null);
    assert.equal(parseDispatchMessage(Buffer.from('{')), null);
    assert.equal(
      parseDispatchMessage(Buffer.from(JSON.stringify({ ...valid, dispatchGeneration: 1.5 }))),
      null,
    );
    assert.equal(parseDispatchMessage(Buffer.from(JSON.stringify({ ...valid, url: 'x' }))), null);
  });

  it('takes the trace ID from a valid traceparent only', () => {
    const id = '4bf92f3577b34da6a3ce929d0e0e4736';
    assert.equal(traceIdOf(`00-${id}-00f067aa0ba902b7-01`), id);
    assert.equal(traceIdOf(`00-${'0'.repeat(32)}-00f067aa0ba902b7-01`), null);
    assert.equal(traceIdOf(`00-${id}-${'0'.repeat(16)}-01`), null);
    assert.equal(traceIdOf(`01-${id}-00f067aa0ba902b7-01`), null);
    assert.equal(traceIdOf(Buffer.from('x')), null);
    assert.equal(traceIdOf(undefined), null);
  });

  it('retry TTL: 1 s × 2ⁿ⁻¹ capped at 30 s, ±20 % jitter', () => {
    const mid = () => 0.5;
    assert.deepEqual(
      [1, 2, 3, 4, 5, 6, 7, 50].map((n) => retryDelayMs(n, mid)),
      [1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000],
    );
    assert.equal(
      retryDelayMs(1, () => 0),
      800,
    );
    assert.equal(
      retryDelayMs(1, () => 0.9999999),
      1200,
    );
    assert.equal(retryDelayMs(0, mid), 1000);
  });
});
