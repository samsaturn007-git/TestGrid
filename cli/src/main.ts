#!/usr/bin/env node
import { parseCommand, USAGE } from './args.ts';
import type { Command } from './args.ts';
import type { Deps } from './client.ts';
import { execute } from './commands.ts';
import { CliError, EXIT } from './exit-codes.ts';
import type { ExitCode } from './exit-codes.ts';

/**
 * Contracts §11. stdout carries the result only (exactly one JSON document with --json); progress
 * and errors go to stderr. Returns the exit code instead of exiting, so tests can call it.
 */
export async function main(argv: readonly string[], deps: Deps): Promise<ExitCode> {
  let command: Command;
  try {
    command = parseCommand(argv, deps.env);
  } catch (error) {
    return fail(error, argv.includes('--json'), argv[0] ?? null, deps);
  }
  if (command.name === 'help') {
    deps.stdout(USAGE);
    return EXIT.PASS;
  }
  try {
    const result = await execute(command, deps);
    deps.stdout(command.json ? JSON.stringify(result.document) + '\n' : result.text);
    return result.exitCode;
  } catch (error) {
    return fail(error, command.json, command.name, deps);
  }
}

function fail(error: unknown, json: boolean, command: string | null, deps: Deps): ExitCode {
  const cli =
    error instanceof CliError
      ? error
      : new CliError(
          EXIT.INDETERMINATE,
          `unexpected error: ${error instanceof Error ? error.message : String(error)}`,
        );
  if (json) {
    deps.stdout(
      JSON.stringify({
        command,
        exitCode: cli.exitCode,
        outcome: cli.exitCode === EXIT.INDETERMINATE ? 'INDETERMINATE' : null,
        error: { message: cli.message, status: cli.status, problemType: cli.problemType },
      }) + '\n',
    );
  }
  deps.stderr(`testgrid: ${cli.message}\n`);
  return cli.exitCode;
}

const isEntry = import.meta.url === new URL(process.argv[1] ?? '', 'file://').href;
if (isEntry) {
  const code = await main(process.argv.slice(2), {
    fetch: globalThis.fetch,
    now: () => Date.now(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    random: Math.random,
    env: process.env,
    stdout: (text) => process.stdout.write(text),
    stderr: (text) => process.stderr.write(text),
  });
  process.exitCode = code;
}
