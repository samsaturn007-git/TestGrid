import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { describe, it } from 'node:test';
import { WorkerApiClient } from '../src/api-client.ts';

const TOKEN = 'fake-service-token-for-tests-0123456789abcdef';
const LEASE = 'Ab3_-Ab3_-Ab3_-Ab3_-Ab3_-Ab3_-Ab3_-Ab3_-Ab3';

// What fetch puts on the wire must be exactly what the API's SingleFilePart reads: one part
// named "file", opened by --boundary and closed by --boundary--, lease token in a header.
describe('artifact upload request (PRD-06 FR-3)', () => {
  it('sends one multipart file part with the lease in a header, never in the URL', async () => {
    let seen: { url: string; headers: Record<string, unknown>; body: Buffer } | undefined;
    const server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (c: Buffer) => chunks.push(c));
      req.on('end', () => {
        seen = { url: req.url ?? '', headers: req.headers, body: Buffer.concat(chunks) };
        res.writeHead(201, { 'content-type': 'application/json' });
        res.end('{"id":"0f8fad5b-d9cb-469f-a165-70867728950e","byteSize":11}');
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    const port = (server.address() as AddressInfo).port;
    try {
      const client = new WorkerApiClient(`http://127.0.0.1:${port}`, TOKEN);
      const content = Buffer.from([
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x0d, 0x0a, 0x2d,
      ]);
      const stored = await client.uploadArtifact(
        '6f9619ff-8b86-4011-b42d-00c04fc964ff',
        LEASE,
        { kind: 'SCREENSHOT', caseKey: 'cart-total', file: new Blob([content]) },
        '0123456789abcdef0123456789abcdef',
      );
      assert.equal(stored.byteSize, 11);
      assert.ok(seen);
      assert.equal(
        seen.url,
        '/internal/v1/attempts/6f9619ff-8b86-4011-b42d-00c04fc964ff/artifacts?kind=SCREENSHOT&caseKey=cart-total',
      );
      assert.ok(!seen.url.includes(LEASE));
      assert.equal(seen.headers['x-testgrid-lease-token'], LEASE);
      assert.equal(seen.headers.authorization, `Bearer ${TOKEN}`);
      const type = String(seen.headers['content-type']);
      const boundary = /^multipart\/form-data; boundary=(.+)$/.exec(type)?.[1];
      assert.ok(boundary, type);
      const head = Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="artifact"\r\n`,
      );
      assert.ok(seen.body.subarray(0, head.length).equals(head), seen.body.toString('latin1'));
      const start = seen.body.indexOf('\r\n\r\n') + 4;
      const end = seen.body.lastIndexOf(Buffer.from(`\r\n--${boundary}--`));
      assert.ok(seen.body.subarray(start, end).equals(content));
      assert.equal(seen.body.indexOf(Buffer.from(`--${boundary}`), head.length), end + 2);
    } finally {
      server.close();
    }
  });
});
