import { inflateRawSync } from 'node:zlib';

// PRD-06 FR-5 / CONFLICT_REGISTER N22: a Playwright trace is a ZIP of DOM snapshots, network logs
// and resources, so redacting inside it is unreliable. Instead the worker looks for every known
// secret value in every entry and withholds a trace that contains one. It fails closed: a trace it
// can't read completely (ZIP64, an unknown compression method, damage, or more than the inflate
// budget) is withheld as well. O(total inflated bytes × number of secrets).

export type ZipScan =
  | { verdict: 'clean'; entries: number }
  | { verdict: 'secret'; entry: string }
  | { verdict: 'unreadable'; reason: string };

/** Stops a "zip bomb" from exhausting the worker's memory. */
export const MAX_INFLATED_BYTES = 256 * 1024 * 1024;

const EOCD = 0x06054b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;

function findEndOfCentralDirectory(zip: Buffer): number {
  // The record is 22 bytes plus a comment of at most 65,535 bytes, at the very end.
  const lowest = Math.max(0, zip.length - 22 - 0xffff);
  for (let i = zip.length - 22; i >= lowest; i--) {
    if (zip.readUInt32LE(i) === EOCD) return i;
  }
  return -1;
}

export function scanZipForSecrets(
  zip: Buffer,
  secrets: readonly string[],
  maxInflated = MAX_INFLATED_BYTES,
): ZipScan {
  const needles = secrets.filter((s) => s.length > 0).map((s) => Buffer.from(s, 'utf8'));
  if (zip.length < 22) return { verdict: 'unreadable', reason: 'too short to be a ZIP' };
  const eocd = findEndOfCentralDirectory(zip);
  if (eocd < 0) return { verdict: 'unreadable', reason: 'no end of central directory' };
  const entries = zip.readUInt16LE(eocd + 10);
  const cdSize = zip.readUInt32LE(eocd + 12);
  const cdOffset = zip.readUInt32LE(eocd + 16);
  if (entries === 0xffff || cdOffset === 0xffffffff || cdSize === 0xffffffff) {
    return { verdict: 'unreadable', reason: 'ZIP64 is not supported' };
  }
  if (cdOffset + cdSize > eocd) return { verdict: 'unreadable', reason: 'bad central directory' };

  let at = cdOffset;
  let inflated = 0;
  for (let n = 0; n < entries; n++) {
    if (at + 46 > eocd || zip.readUInt32LE(at) !== CENTRAL) {
      return { verdict: 'unreadable', reason: 'bad central directory entry' };
    }
    const method = zip.readUInt16LE(at + 10);
    const compressed = zip.readUInt32LE(at + 20);
    const nameLength = zip.readUInt16LE(at + 28);
    const extraLength = zip.readUInt16LE(at + 30);
    const commentLength = zip.readUInt16LE(at + 32);
    const local = zip.readUInt32LE(at + 42);
    const name = zip.subarray(at + 46, at + 46 + nameLength);
    at += 46 + nameLength + extraLength + commentLength;

    if (local + 30 > zip.length || zip.readUInt32LE(local) !== LOCAL) {
      return { verdict: 'unreadable', reason: 'bad local header' };
    }
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    if (start + compressed > zip.length)
      return { verdict: 'unreadable', reason: 'entry overruns the file' };
    const raw = zip.subarray(start, start + compressed);

    let data: Buffer;
    try {
      if (method === 0) data = raw;
      else if (method === 8) {
        data = inflateRawSync(raw, { maxOutputLength: maxInflated - inflated });
      } else return { verdict: 'unreadable', reason: `compression method ${method}` };
    } catch {
      return { verdict: 'unreadable', reason: 'an entry does not inflate within the budget' };
    }
    inflated += data.length;
    for (const needle of needles) {
      if (data.includes(needle) || name.includes(needle)) {
        return { verdict: 'secret', entry: name.toString('utf8') };
      }
    }
  }
  return { verdict: 'clean', entries };
}
