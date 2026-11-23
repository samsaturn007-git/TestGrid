import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { describe, it } from 'node:test';
import { scanZipForSecrets } from '../src/zip-scan.ts';

// A minimal ZIP writer for the tests: local headers, central directory, end record.
function zip(entries: { name: string; data: Buffer; method?: 0 | 8 | 12 }[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const e of entries) {
    const method = e.method ?? 8;
    const body = method === 8 ? deflateRawSync(e.data) : e.data;
    const name = Buffer.from(e.name);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(e.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(e.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, name, body);
    centrals.push(central, name);
    offset += 30 + name.length + body.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(cd.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

const CANARY = 'tg-canary-0123456789abcdef';
const trace = (network: string) =>
  zip([
    { name: 'trace.trace', data: Buffer.from('{"type":"before","title":"click"}\n'.repeat(500)) },
    { name: 'trace.network', data: Buffer.from(network) },
    { name: 'resources/abc.png', data: Buffer.from([0x89, 0x50, 0x4e, 0x47]), method: 0 },
  ]);

describe('trace secret scan (PRD-06 FR-5, CONFLICT_REGISTER N22)', () => {
  it('passes a trace without any secret', () => {
    assert.deepEqual(scanZipForSecrets(trace('{"headers":[]}'), [CANARY]), {
      verdict: 'clean',
      entries: 3,
    });
  });

  it('finds a secret in a deflated entry, e.g. a Cookie request header', () => {
    const result = scanZipForSecrets(
      trace(`{"headers":[{"name":"Cookie","value":"tg_canary=${CANARY}"}]}`),
      ['unrelated-secret-value', CANARY],
    );
    assert.deepEqual(result, { verdict: 'secret', entry: 'trace.network' });
  });

  it('finds a secret in a stored entry and in an entry name', () => {
    assert.equal(
      scanZipForSecrets(zip([{ name: 'a', data: Buffer.from(`x${CANARY}x`), method: 0 }]), [CANARY])
        .verdict,
      'secret',
    );
    assert.equal(
      scanZipForSecrets(zip([{ name: `res/${CANARY}`, data: Buffer.from('x') }]), [CANARY]).verdict,
      'secret',
    );
  });

  it('fails closed on anything it cannot read completely', () => {
    const unknownMethod = zip([{ name: 'a', data: Buffer.from('x'), method: 12 }]);
    assert.equal(scanZipForSecrets(unknownMethod, [CANARY]).verdict, 'unreadable');
    const good = trace('{}');
    assert.equal(
      scanZipForSecrets(good.subarray(0, good.length - 30), [CANARY]).verdict,
      'unreadable',
    );
    assert.equal(
      scanZipForSecrets(Buffer.from('not a zip at all, just text'), [CANARY]).verdict,
      'unreadable',
    );
    const zip64 = Buffer.from(good);
    zip64.writeUInt32LE(0xffffffff, zip64.length - 6);
    assert.equal(scanZipForSecrets(zip64, [CANARY]).verdict, 'unreadable');
  });

  it('withholds a zip bomb instead of inflating it without bound', () => {
    const bomb = zip([{ name: 'bomb', data: Buffer.alloc(2 * 1024 * 1024) }]);
    assert.ok(bomb.length < 10_000);
    assert.deepEqual(scanZipForSecrets(bomb, [CANARY], 1024 * 1024), {
      verdict: 'unreadable',
      reason: 'an entry does not inflate within the budget',
    });
  });
});
