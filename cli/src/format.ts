import type { components } from '../../packages/contracts/src/api.ts';
import type { CaseLine, GateReport } from './commands.ts';

type Schemas = components['schemas'];

/** Left-aligned columns, two spaces apart. */
export function table(header: string[], rows: string[][]): string {
  const widths = header.map((h, i) => Math.max(h.length, ...rows.map((r) => (r[i] ?? '').length)));
  const line = (cells: string[]) =>
    cells
      .map((c, i) => (i === cells.length - 1 ? c : c.padEnd(widths[i] ?? 0)))
      .join('  ')
      .trimEnd();
  return [line(header), ...rows.map(line)].join('\n') + '\n';
}

function value(v: number | null): string {
  return v === null ? '-' : String(v);
}

function caseRows(lines: CaseLine[]): string[][] {
  return lines.map((c) => [
    c.caseKey ?? '-',
    c.engine ?? '-',
    c.status ?? '-',
    c.critical ? 'critical' : '',
  ]);
}

export function qualityText(report: GateReport): string {
  const out: string[] = [];
  out.push(`run ${report.runId}  ${report.suiteKey}  ${report.runStatus}\n`);
  out.push(
    `gate ${report.outcome} (exit ${report.exitCode})` +
      (report.policy ? `  policy v${report.policy.version}` : '') +
      `\n  ${report.reason}\n`,
  );
  if (report.rules.length > 0) {
    out.push(
      '\n' +
        table(
          ['RULE', 'OUTCOME', 'OBSERVED', 'THRESHOLD', 'BLOCKING'],
          report.rules.map((r) => [
            r.ruleId,
            r.outcome,
            value(r.observed),
            value(r.threshold),
            r.blocking ? 'yes' : 'no',
          ]),
        ),
    );
  }
  if (report.failedCases.length > 0) {
    out.push(
      `\nfailed cases (${report.failedCases.length}):\n` +
        indent(table(['CASE', 'ENGINE', 'STATUS', ''], caseRows(report.failedCases))),
    );
  }
  if (report.quarantinedFailures.length > 0) {
    out.push(
      `\nquarantined failures (${report.quarantinedFailures.length}), reported but not counted:\n` +
        indent(table(['CASE', 'ENGINE', 'STATUS', ''], caseRows(report.quarantinedFailures))),
    );
  }
  const inconclusive = report.rules
    .filter((r) => r.outcome === 'INDETERMINATE')
    .flatMap((r) => r.evidence.filter((e) => !e.counted || r.ruleId === 'infra-error'))
    .filter((e) => e.status !== 'PASSED');
  if (inconclusive.length > 0) {
    out.push(
      '\ninconclusive:\n' + inconclusive.map((e) => `  ${e.target ?? '-'}  ${e.detail}\n`).join(''),
    );
  }
  out.push(`\n${report.links.quality}\n`);
  return out.join('');
}

export function statusText(run: Schemas['RunResponse']): string {
  return (
    `run ${run.id}  ${run.suiteKey}@${run.suiteRevision}  ${run.status}  trigger ${run.trigger}\n\n` +
    table(
      ['TARGET', 'STATUS', 'ATTEMPTS', 'LAST ERROR'],
      run.targets.map((t) => [
        t.targetKey,
        t.status,
        `${t.attemptCount}/${t.maxAttempts}`,
        t.latestAttempt?.errorClass ?? '',
      ]),
    )
  );
}

export function compareText(c: Schemas['RunComparisonView']): string {
  const changed = c.rows.filter((r) => r.category !== 'STILL_PASSING');
  return (
    `base ${c.base.id} (${c.base.status})${c.baseIsLastGreen ? ' last green' : ''}\n` +
    `head ${c.head.id} (${c.head.status})\n\n` +
    table(
      ['CATEGORY', 'COUNT'],
      c.counts.map((n) => [n.category, String(n.count)]),
    ) +
    (changed.length === 0
      ? ''
      : '\n' +
        table(
          ['CASE', 'ENGINE', 'CATEGORY', 'BASE', 'HEAD', 'SLOWER'],
          changed.map((r) => [
            r.caseKey,
            r.engine,
            r.category,
            r.baseStatus ?? '-',
            r.headStatus ?? '-',
            r.durationRegression
              ? `${r.baseDurationMs ?? '-'} -> ${r.headDurationMs ?? '-'} ms`
              : '',
          ]),
        ))
  );
}

function indent(text: string): string {
  return text
    .split('\n')
    .map((l) => (l ? `  ${l}` : l))
    .join('\n');
}
