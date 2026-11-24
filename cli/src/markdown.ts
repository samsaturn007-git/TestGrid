import type { CaseLine, GateReport } from './commands.ts';
import type { ExitCode } from './exit-codes.ts';

/**
 * The GitHub job summary (PRD-08 FR-6): outcome, rule table and the failed and quarantined cases.
 * Every value from the API is escaped, so a message can't break the table or inject HTML.
 */
export function gateMarkdown(report: GateReport): string {
  const lines: string[] = [];
  lines.push(
    `## TestGrid gate: ${report.outcome ?? 'INDETERMINATE'} (exit ${report.exitCode})`,
    '',
  );
  lines.push(
    `Run \`${report.runId}\` · ${cell(report.suiteKey)} · run status ${report.runStatus}` +
      (report.policy ? ` · policy v${report.policy.version}` : ''),
    '',
    `> ${cell(report.reason)}`,
    '',
  );
  if (report.rules.length > 0) {
    lines.push('| Rule | Outcome | Observed | Threshold | Blocking |', '|---|---|---|---|---|');
    for (const r of report.rules) {
      lines.push(
        `| ${cell(r.ruleId)} | ${r.blocking && r.outcome !== 'PASS' ? `**${r.outcome}**` : r.outcome} | ${num(r.observed)} | ${num(r.threshold)} | ${r.blocking ? 'yes' : 'no'} |`,
      );
    }
    lines.push('');
  }
  cases(lines, `Failed cases (${report.failedCases.length})`, report.failedCases);
  cases(
    lines,
    `Quarantined failures (${report.quarantinedFailures.length}), reported but not counted`,
    report.quarantinedFailures,
  );
  const inconclusive = report.rules
    .filter((r) => r.outcome === 'INDETERMINATE')
    .flatMap((r) => r.evidence)
    .filter((e) => e.status !== 'PASSED');
  if (inconclusive.length > 0) {
    lines.push('### Inconclusive', '');
    for (const e of inconclusive) {
      lines.push(`- ${cell(e.target ?? '-')}: ${cell(e.detail)}`);
    }
    lines.push('');
  }
  if (report.deadlineReached) {
    lines.push('The run did not finish before the CLI deadline, so the gate is INDETERMINATE.', '');
  }
  lines.push(
    'The dashboard runs inside this job only; screenshots, traces and logs of failed attempts are',
    'in the `testgrid-gate` artifact.',
    '',
  );
  return lines.join('\n');
}

export function errorMarkdown(command: string, exitCode: ExitCode, message: string): string {
  const meaning = exitCode === 2 ? 'INDETERMINATE' : 'configuration or authentication error';
  return [
    `## TestGrid gate: ${meaning} (exit ${exitCode})`,
    '',
    `\`testgrid ${command}\` stopped before an evaluation:`,
    '',
    '```text',
    message.split('\n')[0]?.replaceAll('`', "'") ?? '',
    '```',
    '',
  ].join('\n');
}

function cases(lines: string[], title: string, list: CaseLine[]): void {
  if (list.length === 0) {
    return;
  }
  lines.push(
    `### ${title}`,
    '',
    '| Case | Engine | Status | Critical | Detail |',
    '|---|---|---|---|---|',
  );
  for (const c of list) {
    lines.push(
      `| ${cell(c.caseKey ?? '-')} | ${cell(c.engine ?? '-')} | ${cell(c.status ?? '-')} | ${c.critical ? 'yes' : ''} | ${cell(c.detail)} |`,
    );
  }
  lines.push('');
}

function num(v: number | null): string {
  return v === null ? '-' : String(v);
}

/** One table cell: no line breaks, no pipes, no HTML. */
export function cell(value: string): string {
  return value
    .replace(/[\r\n]+/g, ' ')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('|', '\\|')
    .replaceAll('`', "'")
    .slice(0, 300);
}
