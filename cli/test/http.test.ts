import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, describe, it } from 'node:test';
import type { Deps } from '../src/client.ts';
import { main } from '../src/main.ts';
import { PROJECTS, qualityBody, RUN_ID, runBody } from './support.ts';

/** The real fetch, clock and sleep against a local server, including one that never answers. */
describe('over real HTTP', () => {
  let baseUrl = '';
  const server = createServer((req, res) => {
    const path = req.url ?? '';
    if (path.startsWith('/hang')) {
      return; // never answers: only the per-request timeout and the deadline end this
    }
    const send = (status: number, body: unknown) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (path === '/api/v1/projects') return send(200, PROJECTS);
    if (req.method === 'POST') return send(202, runBody('QUEUED'));
    if (path === `/api/v1/runs/${RUN_ID}`) return send(200, runBody('PASSED'));
    if (path === `/api/v1/runs/${RUN_ID}/quality`) return send(200, qualityBody('PASS'));
    return send(404, {});
  });

  before(async () => {
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });
  after(() => {
    server.closeAllConnections();
    server.close();
  });

  function deps(): Deps & { out: () => string } {
    let out = '';
    return {
      fetch: globalThis.fetch,
      now: () => Date.now(),
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      random: Math.random,
      env: {},
      stdout: (t) => {
        out += t;
      },
      stderr: () => {},
      appendFile: () => {},
      out: () => out,
    };
  }

  it('runs, waits and passes', async () => {
    const d = deps();
    const code = await main(
      [
        'run',
        '--project',
        'demo',
        '--suite',
        'checkout-smoke',
        '--engines',
        'chromium',
        '--wait',
        '--json',
        '--api-url',
        baseUrl,
      ],
      d,
    );
    assert.equal(code, 0);
    assert.equal((JSON.parse(d.out()) as { outcome: string }).outcome, 'PASS');
  });

  it('a server that never answers ends in exit 2 at the deadline', async () => {
    const d = deps();
    const started = Date.now();
    const code = await main(
      ['status', RUN_ID, '--api-url', `${baseUrl}/hang`, '--timeout', '1s'],
      d,
    );
    assert.equal(code, 2);
    assert.ok(Date.now() - started < 5000);
  });
});
