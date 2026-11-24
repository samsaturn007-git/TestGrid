import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { cell } from '../src/markdown.ts';
import { main } from '../src/main.ts';
import { harness, PROJECTS, qualityBody, RUN_ID, runBody } from './support.ts';
import type { Reply } from './support.ts';

const SUMMARY = '/tmp/step-summary.md';

function api(outcome: 'PASS' | 'FAIL' | 'INDETERMINATE') {
  const terminal = outcome === 'PASS' ? 'PASSED' : outcome === 'FAIL' ? 'FAILED' : 'INFRA_ERROR';
  return (method: string, path: string): Reply => {
    if (path === '/projects') return { status: 200, body: PROJECTS };
    if (method === 'POST') return { status: 202, body: runBody('QUEUED') };
    if (path === `/runs/${RUN_ID}`) return { status: 200, body: runBody(terminal) };
    return { status: 200, body: qualityBody(outcome) };
  };
}

const RUN = ['run', '--project', 'demo', '--suite', 'checkout-smoke', '--engines', 'chromium'];

describe('--summary-file', () => {
  it('ci002 writes the rule table and names the failed case and engine', async () => {
    const h = harness(api('FAIL'));
    assert.equal(await main([...RUN, '--wait', '--summary-file', SUMMARY], h.deps), 1);
    const md = h.files.get(SUMMARY) ?? '';
    assert.match(md, /^## TestGrid gate: FAIL \(exit 1\)/);
    assert.match(md, /\| critical-cases \| \*\*FAIL\*\* \| 1 \| 0 \| yes \|/);
    assert.match(md, /### Failed cases \(1\)[\s\S]*\| cart-total \| chromium \| FAILED \| yes \|/);
    assert.match(
      md,
      /### Quarantined failures \(1\)[\s\S]*\| card-declined \| chromium \| QUARANTINED_FAIL \|/,
    );
  });

  it('ci003 explains an indeterminate gate', async () => {
    const h = harness(api('INDETERMINATE'));
    assert.equal(await main(['gate', RUN_ID, '--summary-file', SUMMARY], h.deps), 2);
    assert.match(h.files.get(SUMMARY) ?? '', /### Inconclusive[\s\S]*BROWSER_LAUNCH/);
  });

  it('ci004 records an auth failure, and appends rather than overwrites', async () => {
    const h = harness((method, path) =>
      path === '/projects'
        ? {
            status: 401,
            body: {
              type: 'https://testgrid.local/problems/unauthorized',
              detail: 'the token is not valid',
            },
          }
        : { status: 500 },
    );
    h.files.set(SUMMARY, '# earlier step\n');
    assert.equal(await main([...RUN, '--wait', '--summary-file', SUMMARY], h.deps), 3);
    const md = h.files.get(SUMMARY) ?? '';
    assert.match(
      md,
      /^# earlier step\n## TestGrid gate: configuration or authentication error \(exit 3\)/,
    );
    assert.match(md, /401 the token is not valid/);
  });

  it('a summary that cannot be written never changes the exit code', async () => {
    const h = harness(api('PASS'));
    assert.equal(await main([...RUN, '--wait', '--summary-file', '/unwritable/x.md'], h.deps), 0);
    assert.match(h.stderr(), /could not write the summary/);
  });

  it('is refused on commands without a gate', async () => {
    const h = harness(api('PASS'));
    assert.equal(await main(['status', RUN_ID, '--summary-file', SUMMARY], h.deps), 3);
  });

  it('escapes table breakers and HTML', () => {
    assert.equal(
      cell('a|b\n<img src=x onerror=alert(1)> `c` & d'),
      "a\\|b &lt;img src=x onerror=alert(1)&gt; 'c' &amp; d",
    );
  });
});
