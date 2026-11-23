import { openAsBlob } from 'node:fs';
import { lstat, readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import type { Logger } from 'pino';
import { ApiError } from './api-client.ts';
import type { ArtifactKind, WorkerApiClient } from './api-client.ts';
import type { Completion } from './attempt.ts';
import { redactLog } from './redact.ts';
import type { CaseEvidence } from './report.ts';
import { scanZipForSecrets } from './zip-scan.ts';

// PRD-06 FR-1: after the results are known and before `complete`, upload what explains a
// non-passing attempt: each failing case's screenshot and trace, and the redacted log tail.
// Nothing here can change the result. A failed or refused upload is an ARTIFACT_STORE warning in
// the worker log, and the attempt completes exactly as it would have without it.

export const LOG_TAIL_BYTES = 256 * 1024;
// The API's per-kind limits (contracts §7); a bigger file is skipped instead of sent to be refused.
export const MAX_BYTES: Record<ArtifactKind, number> = {
  SCREENSHOT: 5 * 1024 * 1024,
  TRACE: 50 * 1024 * 1024,
  LOG: 1024 * 1024,
};

export interface EvidenceBundle {
  /** The attempt's Playwright output directory; every file must resolve inside it. */
  outputDir: string;
  cases: readonly CaseEvidence[];
  logTail: string;
}

export interface UploadContext {
  api: Pick<WorkerApiClient, 'uploadArtifact'>;
  attemptId: string;
  leaseToken: string;
  traceId: string;
  secrets: readonly string[];
  log: Logger;
}

export interface UploadSummary {
  uploaded: number;
  skipped: number;
  failed: number;
}

interface Item {
  kind: ArtifactKind;
  caseKey: string | null;
  file: () => Promise<Blob>;
}

/** A fully passing attempt needs no evidence: nothing is uploaded, nothing is stored. */
export function needsEvidence(completion: Completion): boolean {
  if (completion.status !== 'COMPLETED_WITH_RESULTS') return true;
  return completion.caseResults.some((c) => c.status !== 'PASSED' && c.status !== 'SKIPPED');
}

/**
 * The report names these paths and it comes from the child process, so each one is checked: a
 * regular file (not a link) whose real path is inside the attempt's real output directory.
 */
export async function fileInside(dir: string, file: string): Promise<string | null> {
  try {
    const root = await realpath(dir);
    const resolved = path.resolve(dir, file);
    if (!(await lstat(resolved)).isFile()) return null;
    const real = await realpath(resolved);
    return real.startsWith(root + path.sep) ? real : null;
  } catch {
    return null;
  }
}

export async function uploadEvidence(
  ctx: UploadContext,
  bundle: EvidenceBundle,
  completion: Completion,
): Promise<UploadSummary> {
  const summary: UploadSummary = { uploaded: 0, skipped: 0, failed: 0 };
  if (!needsEvidence(completion)) return summary;
  const items: Item[] = [];
  const skip = (kind: ArtifactKind, caseKey: string | null, reason: string) => {
    summary.skipped++;
    ctx.log.warn({ errorClass: 'ARTIFACT_STORE', kind, caseKey, reason }, 'artifact not uploaded');
  };

  const log = redactLog(bundle.logTail, ctx.secrets, LOG_TAIL_BYTES);
  if (log.trim().length > 0) {
    const bytes = Buffer.from(log, 'utf8');
    items.push({ kind: 'LOG', caseKey: null, file: () => Promise.resolve(new Blob([bytes])) });
  }

  for (const evidence of bundle.cases) {
    for (const [kind, reported] of [
      ['SCREENSHOT', evidence.screenshot],
      ['TRACE', evidence.trace],
    ] as const) {
      if (!reported) continue;
      const file = await fileInside(bundle.outputDir, reported);
      if (!file) {
        skip(kind, evidence.caseKey, 'the reported file is not a regular file inside the output');
        continue;
      }
      const size = (await lstat(file)).size;
      if (size === 0 || size > MAX_BYTES[kind]) {
        skip(kind, evidence.caseKey, `size ${size} is outside 1..${MAX_BYTES[kind]} bytes`);
        continue;
      }
      if (kind === 'TRACE') {
        const scan = scanZipForSecrets(await readFile(file), ctx.secrets);
        if (scan.verdict !== 'clean') {
          // N22: a trace with a known secret in it is withheld, never stored.
          skip(
            kind,
            evidence.caseKey,
            scan.verdict === 'secret'
              ? 'trace withheld: it contains a secret value'
              : `trace withheld: ${scan.reason}`,
          );
          continue;
        }
      }
      items.push({ kind, caseKey: evidence.caseKey, file: () => openAsBlob(file) });
    }
  }

  for (const item of items) {
    try {
      const stored = await ctx.api.uploadArtifact(
        ctx.attemptId,
        ctx.leaseToken,
        { kind: item.kind, caseKey: item.caseKey, file: await item.file() },
        ctx.traceId,
      );
      summary.uploaded++;
      ctx.log.info(
        { kind: item.kind, caseKey: item.caseKey, artifactId: stored.id, bytes: stored.byteSize },
        'artifact uploaded',
      );
    } catch (e) {
      summary.failed++;
      ctx.log.warn(
        {
          errorClass: 'ARTIFACT_STORE',
          kind: item.kind,
          caseKey: item.caseKey,
          err: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
        },
        'artifact upload failed; the result is unaffected',
      );
      // The lease is gone or the attempt finished: every further upload would be refused too.
      if (e instanceof ApiError && [404, 409, 410].includes(e.status)) break;
    }
  }
  return summary;
}
