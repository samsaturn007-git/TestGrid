import { pino } from 'pino';
import type { DestinationStream, Logger } from 'pino';
import { redact } from './redact.ts';

// FR-8: one JSON object per line. Attempt-scoped lines carry runId, targetId, attemptId,
// workerId and traceId (child loggers). Token fields are censored by path, and every
// message string is passed through the redactor with the known secrets, so a token can't
// reach the log even through a formatted message.

const REDACT_PATHS = [
  'leaseToken',
  '*.leaseToken',
  'token',
  '*.token',
  'authorization',
  '*.authorization',
  'headers.authorization',
];

export function createLogger(
  secrets: readonly string[],
  base: Record<string, string>,
  destination?: DestinationStream,
): Logger {
  return pino(
    {
      base: { service: 'testgrid-worker', ...base },
      timestamp: pino.stdTimeFunctions.isoTime,
      redact: { paths: REDACT_PATHS, censor: '[REDACTED]' },
      formatters: { level: (label) => ({ level: label }) },
      hooks: {
        logMethod(args, method) {
          const cleaned = args.map((a: unknown) =>
            typeof a === 'string' ? (redact(a, secrets) ?? a) : a,
          );
          method.apply(this, cleaned as Parameters<typeof method>);
        },
      },
    },
    destination,
  );
}
