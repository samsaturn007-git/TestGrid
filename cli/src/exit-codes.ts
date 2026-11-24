/**
 * Contracts §2.4. The gate never answers 0 unless the server evaluated the run and every policy rule
 * passed: a deadline, an unreachable API or an unfinished run is 2, and anything the caller must fix
 * (arguments, configuration, token) is 3.
 */
export const EXIT = { PASS: 0, FAIL: 1, INDETERMINATE: 2, USAGE: 3 } as const;

export type ExitCode = (typeof EXIT)[keyof typeof EXIT];

export type QualityOutcome = 'PASS' | 'FAIL' | 'INDETERMINATE';

export function exitFor(outcome: QualityOutcome): ExitCode {
  switch (outcome) {
    case 'PASS':
      return EXIT.PASS;
    case 'FAIL':
      return EXIT.FAIL;
    case 'INDETERMINATE':
      return EXIT.INDETERMINATE;
  }
}

/** What went wrong, as the exit code and a sentence for stderr or the JSON document. */
export class CliError extends Error {
  readonly exitCode: ExitCode;
  readonly status: number | null;
  readonly problemType: string | null;

  constructor(
    exitCode: ExitCode,
    message: string,
    options: { status?: number; problemType?: string | null } = {},
  ) {
    super(message);
    this.name = 'CliError';
    this.exitCode = exitCode;
    this.status = options.status ?? null;
    this.problemType = options.problemType ?? null;
  }
}
