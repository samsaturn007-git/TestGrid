import path from 'node:path';
import { fileURLToPath } from 'node:url';

// PRD-07 FR-1 (CONFLICT_REGISTER N24): the top stack frames of a failure, as `file:function`,
// for the API's fp_v1 fingerprint. No line numbers, so editing a spec above the failing line
// doesn't split a failure group. Only our own code counts: Playwright, Node and anything outside
// the worker root are skipped. The API checks the same shape again (FingerprintV1.FRAME).

export const MAX_FRAMES = 3;

// Same pattern as the API's FingerprintV1.FRAME.
const FRAME =
  /^(?!\/)(?!(?:.*\/)?\.\.(?:\/|:))[A-Za-z0-9._/-]{1,160}:[A-Za-z_$<][A-Za-z0-9_$.<>-]{0,79}$/;
const FUNCTION = /^[A-Za-z_$<][A-Za-z0-9_$.<>-]{0,79}$/;
// "at fn (file:line:col)" or "at file:line:col".
const AT = /^\s*at (?:(.+?) \()?(\S+?):\d+:\d+\)?\s*$/;

function functionName(raw: string | undefined): string {
  const name = (raw ?? '')
    .replace(/^async /, '')
    .replace(/^new /, '')
    .replace(/ \[as [^\]]*\]$/, '');
  return FUNCTION.test(name) ? name : '<anonymous>';
}

function relativeFile(raw: string, workerRoot: string): string | null {
  let file = raw;
  if (file.startsWith('file://')) {
    try {
      file = fileURLToPath(file);
    } catch {
      return null;
    }
  }
  if (file.startsWith('node:') || !path.isAbsolute(file)) return null;
  const relative = path.relative(workerRoot, file).split(path.sep).join('/');
  if (relative.startsWith('../') || relative === '..' || path.isAbsolute(relative)) return null;
  if (relative.split('/').includes('node_modules')) return null;
  return relative;
}

/** At most MAX_FRAMES `file:function` frames from an Error stack, innermost first. */
export function framesOf(stack: string | undefined, workerRoot: string): string[] {
  if (!stack) return [];
  const frames: string[] = [];
  for (const line of stack.split('\n')) {
    const at = AT.exec(line);
    if (!at?.[2]) continue;
    const file = relativeFile(at[2], workerRoot);
    if (file === null) continue;
    const frame = `${file}:${functionName(at[1])}`;
    if (!FRAME.test(frame)) continue;
    frames.push(frame);
    if (frames.length === MAX_FRAMES) break;
  }
  return frames;
}
