import { z } from 'zod';

// The AMQP dispatch message (contracts §9), validated before anything acts on it. A message
// that doesn't parse is dead-lettered, never guessed at.

export const DispatchMessage = z.strictObject({
  messageId: z.uuid(),
  targetId: z.uuid(),
  dispatchGeneration: z.number().int().min(1),
  schemaVersion: z.literal(1),
});
export type DispatchMessage = z.infer<typeof DispatchMessage>;

export function parseDispatchMessage(body: Buffer): DispatchMessage | null {
  let json: unknown;
  try {
    json = JSON.parse(body.toString('utf8'));
  } catch {
    return null;
  }
  const parsed = DispatchMessage.safeParse(json);
  return parsed.success ? parsed.data : null;
}

const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$/;

/** FR-9: the trace ID from the message's W3C traceparent, or null if it is absent or invalid. */
export function traceIdOf(traceparent: unknown): string | null {
  if (typeof traceparent !== 'string') return null;
  const m = TRACEPARENT.exec(traceparent);
  if (!m?.[1] || /^0+$/.test(m[1]) || /^0+$/.test(m[2] ?? '')) return null;
  return m[1];
}

export const RETRY_BASE_MS = 1000;
export const RETRY_MAX_MS = 30_000;

/**
 * TTL for the n-th trip through the retry queue: 1 s × 2ⁿ⁻¹, capped at 30 s, ±20 % jitter so
 * messages refused together don't come back together.
 */
export function retryDelayMs(retries: number, random: () => number = Math.random): number {
  const n = Math.max(1, Math.floor(retries));
  const base = Math.min(RETRY_BASE_MS * 2 ** Math.min(n - 1, 30), RETRY_MAX_MS);
  return Math.max(1, Math.round(base * (0.8 + random() * 0.4)));
}
