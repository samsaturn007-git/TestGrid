// PRD-08 FR-6: collects what a failed gate needs for diagnosis into one folder for
// actions/upload-artifact: the run, its results, timeline and quality report, and the screenshots,
// traces and logs of every attempt that didn't pass.
//
//   node scripts/gate-artifacts.ts --run <runId> --out gate-artifacts [--api-url http://127.0.0.1:8080]
//
// Bytes are fetched only through the API's signed links (ADR-005) and checked against the stored
// sha256. No token is sent: the reads are public until PRD-12, and the CI token's scopes don't
// cover creating links. Exits 0 even when something is missing, so it never masks the gate's own
// exit code; what it could not fetch is listed in missing.txt.
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import type { components } from '../packages/contracts/src/api.ts';

type Schemas = components['schemas'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEY = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const EXT: Record<Schemas['ArtifactView']['kind'], string> = {
  SCREENSHOT: 'png',
  TRACE: 'zip',
  LOG: 'log',
};
/** Total download budget; one trace is at most 50 MB (contracts §7). */
const BUDGET_BYTES = 300 * 1024 * 1024;

const { values } = parseArgs({
  options: {
    run: { type: 'string' },
    out: { type: 'string' },
    'api-url': { type: 'string', default: 'http://127.0.0.1:8080' },
  },
  strict: true,
});

const runId = values.run ?? '';
if (!UUID.test(runId) || !values.out) {
  process.stderr.write('usage: gate-artifacts.ts --run <runId> --out <dir> [--api-url <url>]\n');
  process.exit(2);
}
const base = new URL(values['api-url']);
const out = path.resolve(values.out);
mkdirSync(out, { recursive: true });
const missing: string[] = [];

async function call(method: string, pathname: string): Promise<Response> {
  return fetch(new URL(pathname, base), {
    method,
    redirect: 'error',
    signal: AbortSignal.timeout(60_000),
  });
}

async function saveJson(name: string, pathname: string): Promise<unknown> {
  try {
    const response = await call('GET', pathname);
    const text = await response.text();
    writeFileSync(path.join(out, name), text);
    if (!response.ok) {
      missing.push(`${name}: HTTP ${response.status}`);
      return null;
    }
    return JSON.parse(text) as unknown;
  } catch (error) {
    missing.push(`${name}: ${String(error)}`);
    return null;
  }
}

await saveJson('run.json', `/api/v1/runs/${runId}`);
await saveJson('quality.json', `/api/v1/runs/${runId}/quality`);
await saveJson('timeline.json', `/api/v1/runs/${runId}/timeline`);
const results = (await saveJson('results.json', `/api/v1/runs/${runId}/results`)) as
  Schemas['RunResults'] | null;

let spent = 0;
for (const target of results?.targets ?? []) {
  for (const attempt of target.attempts) {
    if (attempt.attempt.status === 'PASSED') {
      continue;
    }
    for (const artifact of attempt.artifacts) {
      const caseKey = artifact.caseKey ?? 'attempt';
      const name = `${target.engine}-attempt${attempt.attempt.attemptNo}-${caseKey}-${artifact.kind.toLowerCase()}.${EXT[artifact.kind]}`;
      if (!KEY.test(caseKey) || !UUID.test(artifact.id)) {
        missing.push(`${artifact.id}: unexpected identifiers, skipped`);
        continue;
      }
      if (artifact.state !== 'AVAILABLE') {
        missing.push(`${name}: ${artifact.state}`);
        continue;
      }
      if (spent + artifact.byteSize > BUDGET_BYTES) {
        missing.push(`${name}: over the ${BUDGET_BYTES / 1024 / 1024} MB budget`);
        continue;
      }
      try {
        const link = await call('POST', `/api/v1/artifacts/${artifact.id}/links`);
        if (!link.ok) {
          missing.push(`${name}: link HTTP ${link.status}`);
          continue;
        }
        const { url } = (await link.json()) as { url: string; expiresAt: string };
        // The link is relative to the API origin; refuse anything that would leave it.
        const target = new URL(url, base);
        if (target.origin !== base.origin || !target.pathname.startsWith('/api/v1/artifacts/')) {
          missing.push(`${name}: link outside the API, refused`);
          continue;
        }
        const content = await call('GET', target.pathname + target.search);
        const bytes = Buffer.from(await content.arrayBuffer());
        if (!content.ok) {
          missing.push(`${name}: content HTTP ${content.status}`);
          continue;
        }
        if (createHash('sha256').update(bytes).digest('hex') !== artifact.sha256) {
          missing.push(`${name}: sha256 mismatch, not saved`);
          continue;
        }
        writeFileSync(path.join(out, name), bytes);
        spent += bytes.length;
      } catch (error) {
        missing.push(`${name}: ${String(error)}`);
      }
    }
  }
}

writeFileSync(
  path.join(out, 'missing.txt'),
  missing.length ? missing.join('\n') + '\n' : 'nothing\n',
);
process.stderr.write(
  `gate-artifacts: saved ${Math.round(spent / 1024)} KiB of artifacts to ${out}; ${missing.length} missing\n`,
);
