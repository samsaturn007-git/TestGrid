import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { pino } from 'pino';
import { ApiError } from '../src/api-client.ts';
import type { ArtifactKind } from '../src/api-client.ts';
import type { Completion } from '../src/attempt.ts';
import { fileInside, needsEvidence, uploadEvidence } from '../src/evidence.ts';
import type { UploadContext } from '../src/evidence.ts';

const CANARY = 'tg-canary-0123456789abcdef';
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);

function storedZip(name: string, text: string): Buffer {
  // One stored (uncompressed) entry; enough for the scanner.
  const data = Buffer.from(text);
  const n = Buffer.from(name);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(n.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(n.length, 28);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(46 + n.length, 12);
  end.writeUInt32LE(30 + n.length + data.length, 16);
  return Buffer.concat([local, n, data, central, n, end]);
}

const failed: Completion = {
  status: 'COMPLETED_WITH_RESULTS',
  caseResults: [
    {
      caseKey: 'cart-total',
      status: 'FAILED',
      durationMs: 10,
      retryCount: 1,
      errorClass: 'ASSERTION',
      errorMessage: 'x',
    },
    {
      caseKey: 'add-to-cart',
      status: 'PASSED',
      durationMs: 10,
      retryCount: 0,
      errorClass: null,
      errorMessage: null,
    },
  ],
  workerError: null,
};
const passed: Completion = {
  ...failed,
  caseResults: [{ ...failed.caseResults[1]! }],
};

interface Sent {
  kind: ArtifactKind;
  caseKey: string | null;
  text: string;
}

function context(sent: Sent[], fail?: (n: number) => Error | null) {
  const lines: Record<string, unknown>[] = [];
  const log = pino(
    { level: 'info' },
    { write: (l: string) => void lines.push(JSON.parse(l) as Record<string, unknown>) },
  );
  const ctx: UploadContext = {
    api: {
      uploadArtifact: async (_id, _token, upload) => {
        const error = fail?.(sent.length);
        sent.push({ kind: upload.kind, caseKey: upload.caseKey, text: await upload.file.text() });
        if (error) throw error;
        return { id: '0f8fad5b-d9cb-469f-a165-70867728950e', byteSize: upload.file.size };
      },
    },
    attemptId: 'a',
    leaseToken: 'lease',
    traceId: 't',
    secrets: [CANARY],
    log,
  };
  return { ctx, lines };
}

describe('evidence upload (PRD-06 FR-1)', () => {
  let dir: string;
  let out: string;

  beforeEach(() => {
    dir = mkdtempSync(path.join(tmpdir(), 'tg-evidence-'));
    out = path.join(dir, 'output');
    mkdirSync(path.join(out, 'cart-total'), { recursive: true });
    writeFileSync(path.join(out, 'cart-total', 'test-failed-1.png'), PNG);
    writeFileSync(path.join(out, 'cart-total', 'trace.zip'), storedZip('trace.trace', 'steps'));
  });
  afterEach(() => rmSync(dir, { recursive: true, force: true }));

  const bundle = (logTail = `run log\n[canary] ${CANARY}\n`) => ({
    outputDir: out,
    logTail,
    cases: [
      {
        caseKey: 'cart-total',
        screenshot: path.join(out, 'cart-total', 'test-failed-1.png'),
        trace: path.join(out, 'cart-total', 'trace.zip'),
      },
    ],
  });

  it('needs evidence for anything but a fully passing attempt', () => {
    assert.equal(needsEvidence(passed), false);
    assert.equal(needsEvidence(failed), true);
    assert.equal(needsEvidence({ status: 'TIMED_OUT', caseResults: [], workerError: null }), true);
  });

  it('a passing attempt uploads nothing', async () => {
    const sent: Sent[] = [];
    assert.deepEqual(await uploadEvidence(context(sent).ctx, bundle(), passed), {
      uploaded: 0,
      skipped: 0,
      failed: 0,
    });
    assert.equal(sent.length, 0);
  });

  it('uploads the redacted log, then the screenshot and trace of the failed case', async () => {
    const sent: Sent[] = [];
    const summary = await uploadEvidence(context(sent).ctx, bundle(), failed);
    assert.deepEqual(summary, { uploaded: 3, skipped: 0, failed: 0 });
    assert.deepEqual(
      sent.map((s) => [s.kind, s.caseKey]),
      [
        ['LOG', null],
        ['SCREENSHOT', 'cart-total'],
        ['TRACE', 'cart-total'],
      ],
    );
    assert.equal(sent[0]?.text, 'run log\n[canary] [REDACTED]\n');
    assert.ok(!sent.some((s) => s.text.includes(CANARY)));
  });

  it('withholds a trace that contains a secret and says so', async () => {
    writeFileSync(
      path.join(out, 'cart-total', 'trace.zip'),
      storedZip('trace.network', `Cookie: tg_canary=${CANARY}`),
    );
    const sent: Sent[] = [];
    const { ctx, lines } = context(sent);
    const summary = await uploadEvidence(ctx, bundle(), failed);
    assert.deepEqual(summary, { uploaded: 2, skipped: 1, failed: 0 });
    assert.ok(!sent.some((s) => s.kind === 'TRACE'));
    assert.ok(
      lines.some(
        (l) =>
          l.errorClass === 'ARTIFACT_STORE' &&
          l.reason === 'trace withheld: it contains a secret value',
      ),
    );
  });

  it('a failed upload is a warning and the rest still go; a lost lease stops the rest', async () => {
    const sent: Sent[] = [];
    const { ctx, lines } = context(sent, (n) => (n === 0 ? new Error('connection reset') : null));
    assert.deepEqual(await uploadEvidence(ctx, bundle(), failed), {
      uploaded: 2,
      skipped: 0,
      failed: 1,
    });
    assert.ok(lines.some((l) => l.errorClass === 'ARTIFACT_STORE' && l.kind === 'LOG'));

    const again: Sent[] = [];
    const lost = context(again, () => new ApiError(410, 'lease-expired', 'gone'));
    assert.deepEqual(await uploadEvidence(lost.ctx, bundle(), failed), {
      uploaded: 0,
      skipped: 0,
      failed: 1,
    });
    assert.equal(again.length, 1);
  });

  it('skips files outside the output directory, links, empty and oversized files', async () => {
    const outside = path.join(dir, 'secret.png');
    writeFileSync(outside, PNG);
    symlinkSync(outside, path.join(out, 'cart-total', 'link.png'));
    writeFileSync(path.join(out, 'cart-total', 'empty.png'), '');
    assert.equal(await fileInside(out, outside), null);
    assert.equal(await fileInside(out, path.join(out, '..', 'secret.png')), null);
    assert.equal(await fileInside(out, path.join(out, 'cart-total', 'link.png')), null);
    assert.equal(await fileInside(out, path.join(out, 'cart-total', 'missing.png')), null);
    assert.ok(
      (await fileInside(out, path.join(out, 'cart-total', 'test-failed-1.png')))?.endsWith(
        'test-failed-1.png',
      ),
    );

    const sent: Sent[] = [];
    const summary = await uploadEvidence(
      context(sent).ctx,
      {
        outputDir: out,
        logTail: '',
        cases: [
          {
            caseKey: 'cart-total',
            screenshot: path.join(out, 'cart-total', 'link.png'),
            trace: null,
          },
          {
            caseKey: 'apply-coupon',
            screenshot: path.join(out, 'cart-total', 'empty.png'),
            trace: null,
          },
        ],
      },
      failed,
    );
    assert.deepEqual(summary, { uploaded: 0, skipped: 2, failed: 0 });
  });
});
