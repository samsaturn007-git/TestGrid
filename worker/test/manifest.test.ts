import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { canonicalJson } from '../src/canonical-json.ts';
import { InvalidManifestError, loadManifest, suiteByKey } from '../src/manifest.ts';

const MINI_SPEC = "test('x', () => {});\n";
// Same fixture as the API's ManifestLoaderTest.revisionFollowsTheDocumentedAlgorithm; both
// implementations pin this value, so a drift in either one fails its own test suite.
const MINI_REVISION = '3f3ac231f888';

function miniSuite(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    suiteKey: 'mini',
    displayName: 'Mini',
    specFiles: ['suites/mini.spec.ts'],
    allowedEngines: ['chromium'],
    timeoutMs: 60000,
    playwrightRetries: 0,
    baseUrlRef: 'DEMO_SHOP_INTERNAL',
    fixtureModes: ['PASS'],
    cases: [{ caseKey: 'x', title: 'x', tag: '@x', critical: false }],
    ...overrides,
  };
}

function workerDir(suites: unknown[], raw?: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'tg-manifest-'));
  mkdirSync(path.join(dir, 'manifest'));
  mkdirSync(path.join(dir, 'suites'));
  writeFileSync(path.join(dir, 'suites', 'mini.spec.ts'), MINI_SPEC);
  writeFileSync(
    path.join(dir, 'manifest', 'suites.json'),
    raw ?? JSON.stringify({ schemaVersion: 1, suites }),
  );
  return dir;
}

function rejects(dir: string, message: RegExp): void {
  assert.throws(
    () => loadManifest(dir),
    (e: unknown) => e instanceof InvalidManifestError && message.test(e.message),
  );
}

describe('loadManifest', () => {
  it('loads the repository manifest with both registered suites', () => {
    const suites = loadManifest();
    assert.deepEqual(
      suites.map((s) => [s.suite.suiteKey, s.suite.cases.length]),
      [
        ['checkout-smoke', 6],
        ['catalog-regression', 12],
      ],
    );
    for (const s of suites) assert.match(s.revision, /^[0-9a-f]{12}$/);
    assert.equal(suiteByKey(suites, 'catalog-regression').suite.timeoutMs, 180000);
    assert.throws(() => suiteByKey(suites, 'nope'), InvalidManifestError);
  });

  it('every manifest tag appears exactly once in its spec files', () => {
    for (const { suite } of loadManifest()) {
      const source = suite.specFiles
        .map((f) => readFileSync(path.join(import.meta.dirname, '..', f), 'utf8'))
        .join('\n');
      for (const c of suite.cases) {
        const uses = source.split(`tag: '${c.tag}'`).length - 1;
        assert.equal(uses, 1, `${suite.suiteKey}: ${c.tag} used ${uses} times`);
      }
    }
  });

  it('computes the documented revision (contracts §10)', () => {
    const suite = miniSuite();
    const input =
      'testgrid-suite-revision/v1\n' +
      canonicalJson(suite) +
      '\n' +
      'suites/mini.spec.ts\n' +
      createHash('sha256').update(MINI_SPEC).digest('hex') +
      '\n';
    const expected = createHash('sha256').update(input).digest('hex').slice(0, 12);
    const [loaded] = loadManifest(workerDir([suite]));
    assert.equal(loaded?.revision, expected);
    assert.equal(loaded?.revision, MINI_REVISION);
  });

  it('keeps the revision when only formatting changes', () => {
    const suite = miniSuite();
    const compact = loadManifest(workerDir([suite]))[0]?.revision;
    const pretty = loadManifest(
      workerDir([], JSON.stringify({ suites: [suite], schemaVersion: 1 }, null, 4)),
    )[0]?.revision;
    assert.equal(compact, pretty);
  });

  it('rejects schema violations', () => {
    rejects(workerDir([miniSuite({ extra: 1 })]), /does not match the manifest schema/);
    rejects(workerDir([miniSuite({ allowedEngines: ['edge'] })]), /schema/);
    rejects(workerDir([miniSuite({ baseUrlRef: 'http://evil.test' })]), /schema/);
    rejects(workerDir([miniSuite({ specFiles: ['suites/../x.spec.ts'] })]), /schema/);
    rejects(workerDir([miniSuite({ playwrightRetries: 4 })]), /schema/);
    rejects(workerDir([], '{"schemaVersion": 1, "suites": ['), /not valid JSON/);
  });

  it('rejects duplicate keys and wrong tags', () => {
    rejects(workerDir([miniSuite(), miniSuite()]), /duplicate suiteKey mini/);
    const dupCase = { caseKey: 'x', title: 'again', tag: '@x', critical: false };
    rejects(
      workerDir([
        miniSuite({ cases: [{ caseKey: 'x', title: 'x', tag: '@x', critical: false }, dupCase] }),
      ]),
      /duplicate caseKey x/,
    );
    rejects(
      workerDir([miniSuite({ cases: [{ caseKey: 'x', title: 'x', tag: '@y', critical: false }] })]),
      /must have tag @x/,
    );
  });

  it('rejects missing spec files and symlinks that leave suites/', () => {
    rejects(workerDir([miniSuite({ specFiles: ['suites/absent.spec.ts'] })]), /does not exist/);
    const dir = workerDir([miniSuite({ specFiles: ['suites/escape.spec.ts'] })]);
    writeFileSync(path.join(dir, 'outside.spec.ts'), MINI_SPEC);
    symlinkSync(path.join(dir, 'outside.spec.ts'), path.join(dir, 'suites', 'escape.spec.ts'));
    rejects(dir, /must be a regular file inside/);
  });
});
