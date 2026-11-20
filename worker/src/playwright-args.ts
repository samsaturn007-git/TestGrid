import path from 'node:path';
import type { Engine, ManifestSuite } from './manifest.ts';

// FR-5: the Playwright command line is built only from manifest data. The claim supplies
// keys (engine, case keys), each looked up in the manifest; nothing from the claim is
// copied into an argument, and the result is a typed argv for spawn(), never a shell string.

export class ArgumentError extends Error {
  override name = 'ArgumentError';
}

export interface ArgsInput {
  suite: ManifestSuite;
  engine: string;
  caseKeys: readonly string[];
  configPath: string;
  outputDir: string;
}

const SPEC_FILE = /^suites\/([a-z0-9-]+\/)*[a-z0-9-]+\.spec\.ts$/;
const TAG = /^@[a-z0-9]+(-[a-z0-9]+)*$/;

// Escapes only characters that are special outside a character class, so the pattern is
// valid with or without the regex `u` flag.
function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * --grep matches "<title path> <tags>", so each tag is anchored on whitespace: @cart-total
 * must not also select a future @cart-total-v2. O(c) in the number of claimed cases.
 */
export function grepFor(tags: readonly string[]): string {
  return `(^|\\s)(${tags.map(escapeRegex).join('|')})(\\s|$)`;
}

export function buildPlaywrightArgs(input: ArgsInput): string[] {
  const { suite, engine, caseKeys } = input;
  if (!(suite.allowedEngines as readonly string[]).includes(engine)) {
    throw new ArgumentError(`engine ${engine} is not allowed for suite ${suite.suiteKey}`);
  }
  if (caseKeys.length === 0) {
    throw new ArgumentError('a claim must name at least one case');
  }
  const byKey = new Map(suite.cases.map((c) => [c.caseKey, c]));
  const tags = caseKeys.map((key) => {
    const found = byKey.get(key);
    if (!found) throw new ArgumentError(`case ${key} is not in suite ${suite.suiteKey}`);
    if (!TAG.test(found.tag)) throw new ArgumentError(`case ${key} has an invalid tag`);
    return found.tag;
  });
  if (new Set(caseKeys).size !== caseKeys.length) {
    throw new ArgumentError('duplicate case key in claim');
  }
  for (const spec of suite.specFiles) {
    if (!SPEC_FILE.test(spec)) throw new ArgumentError(`invalid spec path ${spec}`);
  }
  for (const p of [input.configPath, input.outputDir]) {
    if (!path.isAbsolute(p)) throw new ArgumentError(`${p} must be an absolute path`);
  }
  return [
    'test',
    `--config=${input.configPath}`,
    `--project=${engine as Engine}`,
    '--reporter=json',
    `--output=${input.outputDir}`,
    `--retries=${suite.playwrightRetries}`,
    `--grep=${grepFor(tags)}`,
    // Positional filters are regexes over the file path; anchor them at the end.
    ...suite.specFiles.map((spec) => `${escapeRegex(spec)}$`),
  ];
}
