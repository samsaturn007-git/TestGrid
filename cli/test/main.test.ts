import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { main } from '../src/main.ts';
import { harness, PROJECTS, qualityBody, RUN_ID, runBody } from './support.ts';
import type { Reply } from './support.ts';

const RUN = ['run', '--project', 'demo', '--suite', 'checkout-smoke', '--engines', 'chromium'];
const TOKEN = 'tg_ci_0123456789abcdef-never-printed';

/** Answers like the API: the run turns terminal after `pollsUntilDone` reads, then is evaluated. */
function api(outcome: 'PASS' | 'FAIL' | 'INDETERMINATE', pollsUntilDone = 2) {
  let reads = 0;
  const terminal = outcome === 'PASS' ? 'PASSED' : outcome === 'FAIL' ? 'FAILED' : 'INFRA_ERROR';
  return (method: string, path: string): Reply => {
    if (path === '/projects') return { status: 200, body: PROJECTS };
    if (method === 'POST' && path.endsWith('/runs'))
      return { status: 202, body: runBody('QUEUED') };
    if (path === `/runs/${RUN_ID}`) {
      reads++;
      return { status: 200, body: runBody(reads >= pollsUntilDone ? terminal : 'RUNNING') };
    }
    if (path === `/runs/${RUN_ID}/quality`) return { status: 200, body: qualityBody(outcome) };
    return {
      status: 404,
      body: { type: 'https://testgrid.local/problems/not-found', detail: path },
    };
  };
}

describe('testgrid run --wait', () => {
  it('ci001 exits 0 on PASS, sends the CI source and polls with backoff', async () => {
    const h = harness(api('PASS', 3), {
      GITHUB_ACTIONS: 'true',
      GITHUB_REPOSITORY: 'owner/testgrid',
      GITHUB_SHA: '0123456789abcdef0123456789abcdef01234567',
      GITHUB_HEAD_REF: 'feature/x',
      GITHUB_RUN_ID: '987654',
      GITHUB_RUN_ATTEMPT: '1',
      TESTGRID_TOKEN: TOKEN,
    });
    const code = await main([...RUN, '--wait'], h.deps);
    assert.equal(code, 0);
    const create = h.requests.find((r) => r.method === 'POST');
    assert.ok(create);
    assert.deepEqual(create.body, {
      suiteKey: 'checkout-smoke',
      targets: [{ executor: 'LOCAL', engine: 'chromium' }],
      trigger: 'CI',
      idempotencyKey: (create.body as { idempotencyKey: string }).idempotencyKey,
      source: {
        commitSha: '0123456789abcdef0123456789abcdef01234567',
        branch: 'feature/x',
        ciRef: 'gh:987654/1',
      },
    });
    assert.match(
      (create.body as { idempotencyKey: string }).idempotencyKey,
      /^tgcli:[0-9a-f]{64}$/,
    );
    assert.equal(create.headers.Authorization, `Bearer ${TOKEN}`);
    assert.deepEqual(h.sleeps, [1000, 2000]);
    assert.match(h.stdout(), /gate PASS \(exit 0\)\s+policy v3/);
    assert.ok(!h.stdout().includes(TOKEN) && !h.stderr().includes(TOKEN));
  });

  it('ci002 exits 1 on FAIL and names the case and engine', async () => {
    const h = harness(api('FAIL'));
    assert.equal(await main([...RUN, '--wait'], h.deps), 1);
    assert.match(h.stdout(), /gate FAIL \(exit 1\)/);
    assert.match(h.stdout(), /failed cases \(1\):[\s\S]*cart-total\s+chromium\s+FAILED\s+critical/);
    assert.match(
      h.stdout(),
      /quarantined failures \(1\)[\s\S]*card-declined\s+chromium\s+QUARANTINED_FAIL/,
    );
  });

  it('ci003 exits 2 on INDETERMINATE and says why', async () => {
    const h = harness(api('INDETERMINATE'));
    assert.equal(await main([...RUN, '--wait'], h.deps), 2);
    assert.match(h.stdout(), /BROWSER_LAUNCH/);
  });

  it('ci003 exits 2, never 0, when the run outlives the deadline', async () => {
    const h = harness(api('PASS', Number.POSITIVE_INFINITY));
    const code = await main([...RUN, '--wait', '--timeout', '2m', '--json'], h.deps);
    assert.equal(code, 2);
    const doc = JSON.parse(h.stdout()) as Record<string, unknown>;
    assert.equal(doc.outcome, 'INDETERMINATE');
    assert.equal(doc.deadlineReached, true);
    assert.equal(doc.runStatus, 'RUNNING');
    assert.equal(h.elapsed(), 120_000);
    assert.ok(h.sleeps.every((ms) => ms <= 12_000));
  });

  it('ci003 exits 2 when the API stays unreachable until the deadline', async () => {
    const h = harness(() => new TypeError('fetch failed'));
    const code = await main([...RUN, '--wait', '--timeout', '30s'], h.deps);
    assert.equal(code, 2);
    assert.match(h.stderr(), /deadline reached before the API answered GET \/projects/);
    assert.ok(h.requests.length > 3);
  });

  it('retries 5xx, 429 and network errors, and a retried create is the same request', async () => {
    let n = 0;
    const inner = api('PASS', 1);
    const h = harness((method, path, body, count) => {
      if (method === 'POST' && n++ < 2) {
        return n === 1 ? new TypeError('socket hang up') : { status: 503, body: {} };
      }
      if (path === `/runs/${RUN_ID}/quality` && count % 2 === 0) {
        return { status: 429, body: {} };
      }
      return inner(method, path);
    });
    assert.equal(await main([...RUN, '--wait'], h.deps), 0);
    const posts = h.requests.filter((r) => r.method === 'POST');
    assert.equal(posts.length, 3);
    assert.deepEqual(posts[0]?.body, posts[2]?.body);
  });
});

describe('exit 3', () => {
  for (const [status, slug] of [
    [401, 'unauthorized'],
    [403, 'forbidden'],
    [400, 'validation-failed'],
    [409, 'idempotency-conflict'],
    [422, 'fixture-not-allowed'],
  ] as const) {
    it(`ci004 a ${status} ${slug} is exit 3 at once`, async () => {
      const h = harness((method, path) =>
        path === '/projects'
          ? { status: 200, body: PROJECTS }
          : {
              status,
              body: {
                type: `https://testgrid.local/problems/${slug}`,
                title: slug,
                detail: 'nope',
              },
            },
      );
      const code = await main([...RUN, '--wait', '--json'], h.deps);
      assert.equal(code, 3);
      assert.equal(h.requests.length, 2);
      const doc = JSON.parse(h.stdout()) as { error: { status: number; problemType: string } };
      assert.equal(doc.error.status, status);
      assert.equal(doc.error.problemType, slug);
      if (status === 401 || status === 403) {
        assert.match(h.stderr(), /TESTGRID_TOKEN/);
      }
    });
  }

  it('an unknown project is exit 3', async () => {
    const h = harness(() => ({ status: 200, body: { items: [], nextCursor: null } }));
    assert.equal(await main(RUN, h.deps), 3);
  });

  it('bad arguments are exit 3, with one JSON document under --json', async () => {
    const h = harness(() => ({ status: 500 }));
    assert.equal(await main([...RUN, '--engines', 'safari', '--json'], h.deps), 3);
    assert.equal(h.requests.length, 0);
    const doc = JSON.parse(h.stdout()) as { exitCode: number };
    assert.equal(doc.exitCode, 3);
  });
});

describe('other commands', () => {
  it('run without --wait prints the run ID and exits 0 once accepted', async () => {
    const h = harness(api('PASS'));
    assert.equal(await main(RUN, h.deps), 0);
    assert.equal(h.stdout(), `${RUN_ID}\n`);
  });

  it('a replayed run says so', async () => {
    const h = harness((method, path) =>
      method === 'POST' ? { status: 200, body: runBody('PASSED') } : api('PASS')(method, path),
    );
    await main([...RUN, '--json'], h.deps);
    assert.equal((JSON.parse(h.stdout()) as { replayed: boolean }).replayed, true);
    assert.match(h.stderr(), /replayed/);
  });

  it('gate on an unfinished run without --wait is exit 2', async () => {
    const h = harness((method, path) =>
      path.endsWith('/quality')
        ? { status: 200, body: qualityBody(null) }
        : { status: 200, body: runBody('RUNNING') },
    );
    assert.equal(await main(['gate', RUN_ID], h.deps), 2);
    assert.match(h.stdout(), /no evaluation yet/);
  });

  it('gate --json prints exactly one JSON document and logs to stderr', async () => {
    const h = harness(api('FAIL', 2));
    assert.equal(await main(['gate', RUN_ID, '--wait', '--json'], h.deps), 1);
    const lines = h.stdout().trim().split('\n');
    assert.equal(lines.length, 1);
    const doc = JSON.parse(lines[0] ?? '') as {
      failedCases: { caseKey: string; engine: string }[];
    };
    assert.deepEqual(
      doc.failedCases.map((c) => [c.caseKey, c.engine]),
      [['cart-total', 'chromium']],
    );
    assert.match(h.stderr(), /next check in 1 s/);
  });

  it('status prints the targets', async () => {
    const h = harness(() => ({ status: 200, body: runBody('RUNNING') }));
    assert.equal(await main(['status', RUN_ID], h.deps), 0);
    assert.match(h.stdout(), /local:chromium:0\s+RUNNING\s+1\/3/);
  });

  it('compare asks for last green and lists the changes', async () => {
    const ref = { id: RUN_ID, status: 'FAILED' };
    const h = harness(() => ({
      status: 200,
      body: {
        base: { ...ref, status: 'PASSED' },
        head: ref,
        baseIsLastGreen: true,
        counts: [{ category: 'NEW_FAILURE', count: 1 }],
        rows: [
          {
            caseKey: 'cart-total',
            engine: 'chromium',
            category: 'NEW_FAILURE',
            baseStatus: 'PASSED',
            headStatus: 'FAILED',
            durationRegression: false,
          },
          {
            caseKey: 'add-to-cart',
            engine: 'chromium',
            category: 'STILL_PASSING',
            baseStatus: 'PASSED',
            headStatus: 'PASSED',
            durationRegression: false,
          },
        ],
      },
    }));
    assert.equal(await main(['compare', '--base', 'last-green', '--head', RUN_ID], h.deps), 0);
    assert.equal(h.requests[0]?.path, `/runs/compare?base=last-green&head=${RUN_ID}`);
    assert.match(h.stdout(), /cart-total\s+chromium\s+NEW_FAILURE/);
    assert.doesNotMatch(h.stdout(), /add-to-cart/);
  });

  it('help prints usage and exits 0', async () => {
    const h = harness(() => ({ status: 500 }));
    assert.equal(await main(['--help'], h.deps), 0);
    assert.match(h.stdout(), /exit:\s+0 PASS, 1 FAIL, 2 INDETERMINATE/);
  });
});
