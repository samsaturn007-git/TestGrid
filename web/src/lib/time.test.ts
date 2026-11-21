import { describe, expect, it } from 'vitest';
import { formatAbsolute, formatAgo, formatDuration } from './time.ts';

const NOW = Date.parse('2026-11-22T12:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe('time formatting', () => {
  it.each([
    [0, '0 s ago'],
    [9_000, '9 s ago'],
    [59_000, '59 s ago'],
    [60_000, '1 min ago'],
    [13 * 60_000, '13 min ago'],
    [60 * 60_000, '1 h ago'],
    [67 * 60_000, '1 h 7 min ago'],
    [24 * 3_600_000, '1 day ago'],
    [3 * 24 * 3_600_000, '3 days ago'],
    [-5_000, '0 s ago'],
  ])('%i ms ago reads %s', (ms, text) => {
    expect(formatAgo(ago(ms), NOW)).toBe(text);
  });

  it.each([
    [0, '0.0 s'],
    [42_000, '42.0 s'],
    [59_949, '59.9 s'],
    [121_000, '2 min 01 s'],
    [3_723_000, '1 h 02 min'],
  ])('%i ms lasts %s', (ms, text) => {
    expect(formatDuration(ms)).toBe(text);
  });

  it('shows absolute times in UTC, whatever the viewer’s zone', () => {
    expect(formatAbsolute('2026-11-20T12:04:22Z')).toBe('Nov 20, 2026, 12:04:22 UTC');
  });
});
