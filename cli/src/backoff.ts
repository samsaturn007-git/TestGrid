/**
 * PRD-08 FR-4 polling delays: 1 s doubling to a 10 s cap, each with ±20 % jitter so many CI jobs
 * started together don't poll in lockstep. `random` is injectable for tests.
 */
export const BACKOFF = { initialMs: 1000, maxMs: 10_000, factor: 2, jitter: 0.2 } as const;

export function* backoffDelays(random: () => number = Math.random): Generator<number> {
  let base: number = BACKOFF.initialMs;
  for (;;) {
    const spread = 1 + BACKOFF.jitter * (2 * random() - 1);
    yield Math.round(base * spread);
    base = Math.min(base * BACKOFF.factor, BACKOFF.maxMs);
  }
}

/** `15m`, `90s`, `1h`, `500ms` or a bare number of seconds. At most 6 h, at least 1 s. */
export function parseDuration(value: string): number | null {
  const match = /^([0-9]{1,6})(ms|s|m|h)?$/.exec(value.trim());
  if (!match?.[1]) {
    return null;
  }
  const n = Number(match[1]);
  const unit = match[2] ?? 's';
  const ms =
    unit === 'ms' ? n : unit === 's' ? n * 1000 : unit === 'm' ? n * 60_000 : n * 3_600_000;
  return ms >= 1000 && ms <= 6 * 3_600_000 ? ms : null;
}
