import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { canonicalJson } from './canonical-json.ts';

// Worker-side manifest loader (contracts §10, PRD-03 FR-1). The API validates the same
// file at startup; the worker repeats every check because it is the process that turns
// manifest entries into Playwright arguments, so it must not trust an unchecked file.

export type Engine = 'chromium' | 'firefox' | 'webkit';
export const ENGINES: readonly Engine[] = ['chromium', 'firefox', 'webkit'];

export interface ManifestCase {
  caseKey: string;
  title: string;
  tag: string;
  critical: boolean;
  expectedDurationMs?: number;
}

export interface ManifestSuite {
  suiteKey: string;
  displayName: string;
  enabled?: boolean;
  specFiles: string[];
  allowedEngines: Engine[];
  timeoutMs: number;
  playwrightRetries: number;
  baseUrlRef: 'DEMO_SHOP_INTERNAL';
  fixtureModes: string[];
  cases: ManifestCase[];
}

export interface LoadedSuite {
  suite: ManifestSuite;
  revision: string;
}

export class InvalidManifestError extends Error {
  override name = 'InvalidManifestError';
}

const REVISION_PREFIX = 'testgrid-suite-revision/v1\n';
const MAX_FILE_BYTES = 1024 * 1024;
const MAX_REPORTED_ERRORS = 20;

export const DEFAULT_WORKER_ROOT = fileURLToPath(new URL('..', import.meta.url));
export const DEFAULT_SCHEMA_PATH = fileURLToPath(
  new URL('../../packages/contracts/schema/suite-manifest.schema.json', import.meta.url),
);

function readBounded(file: string, what: string): Buffer {
  let size: number;
  try {
    size = statSync(file).size;
  } catch {
    throw new InvalidManifestError(`${what} ${file} does not exist`);
  }
  if (size > MAX_FILE_BYTES) throw new InvalidManifestError(`${what} ${file} is larger than 1 MiB`);
  return readFileSync(file);
}

function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

export function loadManifest(
  workerRoot: string = DEFAULT_WORKER_ROOT,
  schemaPath: string = DEFAULT_SCHEMA_PATH,
): LoadedSuite[] {
  const root = realpathSync(workerRoot);
  const file = path.join(root, 'manifest', 'suites.json');
  const schema = JSON.parse(readBounded(schemaPath, 'manifest schema').toString('utf8')) as object;

  let tree: unknown;
  try {
    tree = JSON.parse(readBounded(file, 'manifest').toString('utf8'));
  } catch (e) {
    if (e instanceof InvalidManifestError) throw e;
    throw new InvalidManifestError(`${file} is not valid JSON`);
  }

  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validate = ajv.compile(schema);
  if (!validate(tree)) {
    const errors = validate.errors ?? [];
    const details = errors
      .slice(0, MAX_REPORTED_ERRORS)
      .map((e) => `  ${e.instancePath || '/'}: ${e.message ?? 'invalid'}`)
      .join('\n');
    throw new InvalidManifestError(
      `${file} does not match the manifest schema (${errors.length} errors):\n${details}`,
    );
  }

  // The schema just proved the shape; the rest are rules JSON Schema can't express.
  const manifest = tree as { suites: ManifestSuite[] };
  const seenSuites = new Set<string>();
  const suitesDir = realpathSync(path.join(root, 'suites'));

  return manifest.suites.map((suite, i) => {
    const where = `${file} $.suites[${i}] (${suite.suiteKey})`;
    if (seenSuites.has(suite.suiteKey)) {
      throw new InvalidManifestError(`${file}: duplicate suiteKey ${suite.suiteKey}`);
    }
    seenSuites.add(suite.suiteKey);

    const seenCases = new Set<string>();
    for (const c of suite.cases) {
      if (seenCases.has(c.caseKey)) {
        throw new InvalidManifestError(`${where}: duplicate caseKey ${c.caseKey}`);
      }
      seenCases.add(c.caseKey);
      if (c.tag !== `@${c.caseKey}`) {
        throw new InvalidManifestError(
          `${where}: case ${c.caseKey} must have tag @${c.caseKey}, not ${c.tag}`,
        );
      }
    }

    let input = REVISION_PREFIX + canonicalJson(suite) + '\n';
    for (const spec of suite.specFiles) {
      input += `${spec}\n${sha256Hex(readSpec(where, root, suitesDir, spec))}\n`;
    }
    return { suite, revision: sha256Hex(input).slice(0, 12) };
  });
}

function readSpec(where: string, root: string, suitesDir: string, spec: string): Buffer {
  let real: string;
  try {
    real = realpathSync(path.join(root, spec));
  } catch {
    throw new InvalidManifestError(`${where}: spec file ${spec} does not exist`);
  }
  // The schema forbids "..", but a symlink could still lead outside suites/; compare real paths.
  if (!real.startsWith(suitesDir + path.sep) || !lstatSync(real).isFile()) {
    throw new InvalidManifestError(
      `${where}: spec file ${spec} must be a regular file inside ${suitesDir}`,
    );
  }
  return readBounded(real, 'spec file');
}

export function suiteByKey(suites: readonly LoadedSuite[], suiteKey: string): LoadedSuite {
  const found = suites.find((s) => s.suite.suiteKey === suiteKey);
  if (!found) throw new InvalidManifestError(`suite ${suiteKey} is not in the manifest`);
  return found;
}
