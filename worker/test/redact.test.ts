import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { MASK, redact, redactLog, secretsFromEnv } from '../src/redact.ts';

const CANARY = 'tg-canary-0123456789abcdef';

describe('redaction (PRD-06 FR-5)', () => {
  it('masks Authorization and Cookie header values, bearer tokens and JWTs', () => {
    assert.equal(redact('Authorization: Basic dXNlcjpwYXNz'), `Authorization: ${MASK}`);
    assert.equal(redact('cookie: sid=abc; tg_canary=x'), `cookie: ${MASK}`);
    assert.equal(redact('{"Set-Cookie":"sid=abc"}'), `{"Set-Cookie":"${MASK}"}`);
    assert.equal(redact('got bearer abc.def'), `got Bearer ${MASK}`);
    assert.equal(redact('jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln ok'), `jwt ${MASK} ok`);
  });

  it('masks every given secret value, the canary included', () => {
    assert.equal(redact(`page shows ${CANARY}!`, [CANARY]), `page shows ${MASK}!`);
  });

  it('collects the values of *_TOKEN, *_KEY, *_SECRET and *_PASSWORD variables', () => {
    const secrets = secretsFromEnv({
      TESTGRID_CANARY_SECRET: CANARY,
      RABBITMQ_PASSWORD: 'local-dev-only-rabbitmq',
      TESTGRID_WORKER_TOKEN: 'local-dev-only-worker-token',
      BROWSERSTACK_ACCESS_KEY: 'bs-key-value-123',
      SHORT_TOKEN: 'abc',
      KEYBOARD_LAYOUT: 'us-international',
      HOME: '/home/pwuser',
    });
    assert.deepEqual(secrets, [
      'local-dev-only-worker-token',
      CANARY,
      'local-dev-only-rabbitmq',
      'bs-key-value-123',
    ]);
  });

  it('the log tail keeps lines, drops colours and NUL, and redacts before cutting', () => {
    const raw = `\u001b[31m  1) cart total\u001b[39m\n\tCookie: a=b\n[canary] ${CANARY}\u0000\n`;
    assert.equal(
      redactLog(raw, [CANARY], 1024),
      `  1) cart total\n\tCookie: ${MASK}\n[canary] ${MASK}\n`,
    );
  });

  it('cuts the log tail to the byte limit on a character boundary', () => {
    const out = redactLog('ä'.repeat(100) + 'end', [], 11);
    assert.ok(Buffer.byteLength(out) <= 11);
    assert.ok(out.endsWith('end'));
    assert.ok(!out.includes('�'));
  });
});
