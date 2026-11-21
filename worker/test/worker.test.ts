import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { IncomingHttpHeaders } from 'node:http';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { Writable } from 'node:stream';
import { describe, it } from 'node:test';
import { WorkerApiClient } from '../src/api-client.ts';
import { createLogger } from '../src/logger.ts';
import { DEFAULT_WORKER_ROOT, loadManifest, suiteByKey } from '../src/manifest.ts';
import { handleDelivery } from '../src/worker.ts';

const TOKEN = 'fake-service-token-for-tests-0123456789abcdef';
const LEASE = 'L'.repeat(20) + 'e'.repeat(23);
const suites = loadManifest();
const revision = suiteByKey(suites, 'checkout-smoke').revision;

interface Call {
  path: string;
  headers: IncomingHttpHeaders;
  body: Record<string, unknown>;
}

interface Script {
  /** HTTP status for the claim; 200 (default) returns a claim. */
  claim?: number;
  mode?: string;
  seed?: number;
  heartbeat?: (n: number) => { status: number; body?: unknown };
  complete?: (n: number) => number;
}

async function fakeApi(script: Script) {
  const calls: Call[] = [];
  let claims = 0;
  let beats = 0;
  let completes = 0;
  const server = createServer((req, res) => {
    let raw = '';
    req.on('data', (c: Buffer) => (raw += c.toString()));
    req.on('end', () => {
      const body = JSON.parse(raw || '{}') as Record<string, unknown>;
      calls.push({ path: req.url ?? '', headers: req.headers, body });
      const send = (status: number, payload?: unknown) => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(payload === undefined ? undefined : JSON.stringify(payload));
      };
      if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(401, {});
      const url = req.url ?? '';
      if (url.endsWith('/claim')) {
        claims++;
        if (script.claim && script.claim !== 200) {
          const slug = { 409: 'stale-dispatch', 429: 'capacity-exhausted' }[script.claim];
          return send(
            script.claim,
            slug ? { type: `https://testgrid.local/problems/${slug}` } : {},
          );
        }
        return send(200, {
          attemptId: '00000000-0000-4000-8000-0000000000a1',
          attemptNo: 1,
          runId: '00000000-0000-4000-8000-0000000000b1',
          targetId: '00000000-0000-4000-8000-0000000000c1',
          leaseToken: LEASE,
          leaseTtlSeconds: 30,
          heartbeatIntervalSeconds: 1,
          suite: { suiteKey: 'checkout-smoke', revision },
          engine: 'chromium',
          caseKeys: [
            'add-to-cart',
            'apply-coupon',
            'browse-catalog',
            'card-declined',
            'cart-total',
            'checkout-happy-path',
          ],
          fixture: { mode: script.mode ?? 'PRODUCT_DEFECT', seed: script.seed ?? 42 },
          timeoutMs: 120000,
        });
      }
      if (url.endsWith('/start'))
        return send(200, { attemptId: 'x', status: 'RUNNING', leasedUntil: 'now' });
      if (url.endsWith('/heartbeat')) {
        const r = script.heartbeat?.(++beats) ?? { status: 200 };
        return send(r.status, r.body ?? { leasedUntil: 'later', cancelRequested: false });
      }
      if (url.endsWith('/complete')) {
        const status = script.complete?.(++completes) ?? 200;
        return send(status, {
          attemptStatus: 'FAILED',
          targetStatus: 'FAILED',
          runStatus: 'FAILED',
          errorClass: 'ASSERTION',
        });
      }
      send(404, {});
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return { url, calls, claims: () => claims, close: () => new Promise((r) => server.close(r)) };
}

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const TARGET = '00000000-0000-4000-8000-0000000000c1';
const MESSAGE = '00000000-0000-4000-8000-0000000000d1';

function body(overrides: Record<string, unknown> = {}): Buffer {
  return Buffer.from(
    JSON.stringify({
      messageId: MESSAGE,
      targetId: TARGET,
      dispatchGeneration: 2,
      schemaVersion: 1,
      ...overrides,
    }),
  );
}

async function run(
  script: Script,
  opts: { stopAfterMs?: number; message?: Buffer; traceparent?: unknown } = {},
) {
  const api = await fakeApi(script);
  const lines: string[] = [];
  const sink = new Writable({
    write(chunk: Buffer, _enc, done) {
      lines.push(...chunk.toString().split('\n').filter(Boolean));
      done();
    },
  });
  const stopping = new AbortController();
  if (opts.stopAfterMs !== undefined) setTimeout(() => stopping.abort(), opts.stopAfterMs);
  const decision = await handleDelivery(
    {
      api: new WorkerApiClient(api.url, TOKEN),
      log: createLogger([TOKEN], { workerId: 'w-test' }, sink),
      workerId: 'w-test',
      attemptDeps: {
        suites,
        workerRoot: DEFAULT_WORKER_ROOT,
        configPath: path.join(DEFAULT_WORKER_ROOT, 'playwright.config.ts'),
        baseUrl: 'http://127.0.0.1:4173',
        secrets: [TOKEN],
        parentEnv: { ...process.env, TESTGRID_WORKER_TOKEN: TOKEN },
        killGraceMs: 200,
        cliPath: path.join(import.meta.dirname, 'fixtures', 'fake-cli', 'replay.mjs'),
      },
      shutdownGraceMs: 300,
      stopping: stopping.signal,
    },
    opts.message ?? body(),
    'traceparent' in opts ? opts.traceparent : `00-${TRACE}-00f067aa0ba902b7-01`,
  );
  await api.close();
  const logs = lines.map((l) => JSON.parse(l) as Record<string, unknown>);
  const completes = api.calls.filter((c) => c.path.endsWith('/complete'));
  return { decision, calls: api.calls, completes, logs, raw: lines.join('\n') };
}

describe('handleDelivery against a fake worker API', () => {
  it('claims the named target, runs it, completes it, then acks', async () => {
    const { calls, completes, decision } = await run({});
    assert.deepEqual(
      calls.map((c) => c.path.split('/').pop()),
      ['claim', 'start', 'complete'],
    );
    assert.equal(calls[0]?.path, `/internal/v1/targets/${TARGET}/claim`);
    assert.deepEqual(calls[0]?.body, {
      workerId: 'w-test',
      messageId: MESSAGE,
      dispatchGeneration: 2,
    });
    assert.deepEqual(decision, { action: 'ack', reason: 'attempt finished' });
    // FR-9: every call continues the trace from the message's traceparent.
    const traceIds = new Set(calls.map((c) => String(c.headers.traceparent).split('-')[1]));
    assert.deepEqual([...traceIds], [TRACE]);
    for (const c of calls)
      assert.match(String(c.headers.traceparent), /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    const body = completes[0]?.body;
    assert.equal(body?.completionKey, 'attempt-00000000-0000-4000-8000-0000000000a1-final');
    assert.equal(body?.status, 'COMPLETED_WITH_RESULTS');
    assert.equal(body?.leaseToken, LEASE);
  });

  it('FR-8: attempt log lines carry the five IDs and no token ever reaches the log', async () => {
    const { logs, raw } = await run({});
    // claimed, attempt finished, completion accepted
    assert.ok(logs.length >= 3);
    const scoped = logs.filter((l) => 'attemptId' in l);
    assert.ok(scoped.length >= 3);
    for (const line of scoped) {
      for (const key of ['runId', 'targetId', 'attemptId', 'workerId', 'traceId']) {
        assert.ok(
          typeof line[key] === 'string' && line[key] !== '',
          `${key} missing in ${JSON.stringify(line)}`,
        );
      }
    }
    assert.ok(!raw.includes(TOKEN), 'service token in logs');
    assert.ok(!raw.includes(LEASE), 'lease token in logs');
  });

  it('the logger censors token fields and masks secrets inside messages', () => {
    const lines: string[] = [];
    const sink = new Writable({
      write(chunk: Buffer, _e, done) {
        lines.push(chunk.toString());
        done();
      },
    });
    const log = createLogger([TOKEN], {}, sink);
    log.info({ leaseToken: LEASE, nested: { token: TOKEN } }, `oops ${TOKEN} and Bearer ${TOKEN}`);
    const out = lines.join('');
    assert.ok(!out.includes(TOKEN) && !out.includes(LEASE), out);
    assert.match(out, /\[REDACTED\]/);
  });

  it('a cancel request from the heartbeat stops the run and reports CANCELLED', async () => {
    const { completes } = await run({
      seed: 3,
      mode: 'PASS',
      heartbeat: () => ({ status: 200, body: { leasedUntil: 'x', cancelRequested: true } }),
    });
    assert.equal(completes[0]?.body.status, 'CANCELLED');
  });

  it('a lost lease stops the run and sends no completion', async () => {
    const { completes, calls } = await run({
      seed: 3,
      mode: 'PASS',
      heartbeat: () => ({
        status: 410,
        body: { type: 'https://testgrid.local/problems/lease-expired' },
      }),
    });
    assert.equal(completes.length, 0);
    assert.ok(calls.some((c) => c.path.endsWith('/heartbeat')));
  });

  it('FR-6: SIGTERM mid-run gives the attempt the grace period, then reports WORKER_SHUTDOWN', async () => {
    const { completes, decision } = await run({ seed: 3, mode: 'PASS' }, { stopAfterMs: 200 });
    assert.equal(completes[0]?.body.status, 'INFRA_ERROR');
    assert.deepEqual(
      (completes[0]?.body.workerError as { errorClass: string }).errorClass,
      'WORKER_SHUTDOWN',
    );
    assert.equal(decision.action, 'ack');
  });

  it('retries a completion that failed with 5xx, using the same key', async () => {
    const { completes } = await run({ complete: (n) => (n === 1 ? 503 : 200) });
    assert.equal(completes.length, 2);
    assert.equal(completes[0]?.body.completionKey, completes[1]?.body.completionKey);
  });

  it('a 409 stale dispatch is acked without running anything', async () => {
    const { calls, decision } = await run({ claim: 409 });
    assert.deepEqual(decision, { action: 'ack', reason: 'stale' });
    assert.deepEqual(
      calls.map((c) => c.path.split('/').pop()),
      ['claim'],
    );
  });

  it('a 429 at the cap goes to the retry queue', async () => {
    const { decision } = await run({ claim: 429 });
    assert.deepEqual(decision, { action: 'retry', reason: 'capacity' });
  });

  it('an API 5xx or 401 keeps the message (retry), a 400 dead-letters it', async () => {
    assert.equal((await run({ claim: 503 })).decision.action, 'retry');
    assert.equal((await run({ claim: 401 })).decision.action, 'retry');
    assert.equal((await run({ claim: 400 })).decision.action, 'dead-letter');
    assert.equal((await run({ claim: 404 })).decision.action, 'ack');
  });

  it('an unreachable API keeps the message (retry)', async () => {
    const deps = {
      api: new WorkerApiClient('http://127.0.0.1:9', TOKEN),
      log: createLogger([TOKEN], {}, new Writable({ write: (_c, _e, d) => d() })),
      workerId: 'w',
      attemptDeps: {} as never,
      shutdownGraceMs: 0,
      stopping: new AbortController().signal,
    };
    assert.equal((await handleDelivery(deps, body(), undefined)).action, 'retry');
  });

  it('a message that does not parse is dead-lettered and never claimed', async () => {
    for (const message of [
      Buffer.from('not json'),
      body({ schemaVersion: 2 }),
      body({ dispatchGeneration: 0 }),
      body({ targetId: '../../etc' }),
      body({ extra: true }),
    ]) {
      const { decision, calls } = await run({}, { message });
      assert.deepEqual(decision, { action: 'dead-letter', reason: 'unparseable' });
      assert.equal(calls.length, 0);
    }
  });

  it('a missing or invalid traceparent starts a new trace', async () => {
    const { calls } = await run({ claim: 409 }, { traceparent: 'garbage' });
    const traceId = String(calls[0]?.headers.traceparent).split('-')[1];
    assert.match(traceId ?? '', /^[0-9a-f]{32}$/);
    assert.notEqual(traceId, TRACE);
  });
});
