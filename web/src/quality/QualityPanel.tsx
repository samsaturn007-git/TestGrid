import { Link } from 'react-router';
import { isTerminal, useRunQuality } from '../api/queries.ts';
import type {
  QualityRuleEvidence,
  QualityRuleResult,
  RunQualityView,
  RunResponse,
} from '../api/types.ts';
import { ErrorState } from '../components/States.tsx';
import { formatAbsolute } from '../lib/time.ts';
import { EXIT_CODE, QualityBadge, QuarantinedChip } from './QualityBadge.tsx';

/**
 * PRD-08 FR-7: the run's quality gate. Every number comes from GET /runs/{id}/quality; nothing
 * is recomputed here, so the panel says exactly what the CLI's exit code was based on.
 */
export function QualityPanel({ run }: { run: RunResponse }) {
  const finished = isTerminal(run.status);
  const quality = useRunQuality(run.id, finished);
  return (
    <section
      aria-labelledby="quality-gate"
      className="overflow-hidden rounded-card border border-hairline bg-surface"
    >
      <h2 id="quality-gate" className="sr-only">
        Quality gate
      </h2>
      {!finished ? (
        <p className="px-4 py-3.5 text-body text-text-muted">
          Quality gate: evaluated once the run finishes, against the active policy.
        </p>
      ) : quality.error ? (
        <div className="p-4">
          <ErrorState error={quality.error} />
        </div>
      ) : !quality.data || quality.data.outcome === null ? (
        <p role="status" className="px-4 py-3.5 text-body text-text-muted">
          Quality gate: evaluating…
        </p>
      ) : (
        <Evaluated quality={quality.data} projectId={run.projectId} />
      )}
    </section>
  );
}

function headline(quality: RunQualityView): string {
  const failing = (outcome: string) =>
    quality.rules.filter((r) => r.blocking && r.outcome === outcome).length;
  const plural = (n: number) => (n === 1 ? 'rule' : 'rules');
  switch (quality.outcome) {
    case 'FAIL': {
      const n = failing('FAIL');
      return `${n} blocking ${plural(n)} failed`;
    }
    case 'INDETERMINATE': {
      const n = failing('INDETERMINATE');
      return `Couldn't decide: ${n} blocking ${plural(n)} inconclusive`;
    }
    default:
      return 'Every blocking rule passed';
  }
}

function Evaluated({ quality, projectId }: { quality: RunQualityView; projectId: string }) {
  const outcome = quality.outcome ?? 'INDETERMINATE';
  const inconclusive = quality.rules
    .filter((r) => r.blocking && r.outcome === 'INDETERMINATE')
    .flatMap((r) => r.evidence)
    .filter((e) => e.status !== 'PASSED');
  const quarantined =
    quality.rules.find((r) => r.ruleId === 'quarantine-honored')?.evidence.length ?? 0;
  return (
    <>
      <div className="flex flex-wrap items-center gap-3 border-b border-hairline px-4 py-3.5">
        <QualityBadge outcome={outcome} size="large" />
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="text-body font-semibold">{headline(quality)}</span>
          <span className="font-mono text-[11px] text-text-secondary">
            {quality.policy ? (
              <Link
                to={`/projects/${projectId}/policy?version=${quality.policy.version}`}
                className="underline"
              >
                policy v{quality.policy.version}
              </Link>
            ) : null}
            {quality.evaluatedAt ? (
              <>
                {' · evaluated '}
                <time dateTime={quality.evaluatedAt}>{formatAbsolute(quality.evaluatedAt)}</time>
              </>
            ) : null}
            {` · CLI exit ${EXIT_CODE[outcome]}`}
          </span>
        </span>
      </div>
      {outcome === 'INDETERMINATE' && inconclusive.length > 0 ? (
        <div className="border-b border-hairline px-4 py-3 text-body text-text-secondary">
          <p>Couldn't decide, because there was no test evidence for:</p>
          <ul className="mt-1 list-disc pl-5">
            {inconclusive.map((e, i) => (
              <li key={i}>
                <span className="font-mono">{e.target ?? '-'}</span> {e.detail}
              </li>
            ))}
          </ul>
          <p className="mt-1">CI should retry the run, not treat it as a test failure.</p>
        </div>
      ) : null}
      {quarantined > 0 ? (
        <p className="border-b border-hairline px-4 py-3 text-body text-text-secondary">
          {quarantined === 1 ? '1 quarantined failure' : `${quarantined} quarantined failures`}{' '}
          reported but not counted.{' '}
          <Link to="/quarantines" className="underline">
            See quarantines
          </Link>
        </p>
      ) : null}
      <table className="w-full border-collapse text-left" aria-label="Quality rules">
        <thead>
          <tr className="border-b border-hairline bg-canvas font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
            <th scope="col" className="px-4 py-2 font-medium">
              RULE
            </th>
            <th scope="col" className="px-2 py-2 font-medium">
              OBSERVED
            </th>
            <th scope="col" className="px-2 py-2 font-medium">
              THRESHOLD
            </th>
            <th scope="col" className="px-2 py-2 font-medium">
              OUTCOME
            </th>
            <th scope="col" className="px-4 py-2 font-medium">
              BLOCKS
            </th>
          </tr>
        </thead>
        <tbody>
          {quality.rules.map((rule) => (
            <RuleRow key={rule.ruleId} rule={rule} />
          ))}
        </tbody>
      </table>
    </>
  );
}

function value(v: number | null): string {
  return v === null ? '–' : String(v);
}

function RuleRow({ rule }: { rule: QualityRuleResult }) {
  return (
    <tr className="border-b border-hairline-soft align-top font-mono text-mono-sm last:border-b-0">
      <th scope="row" className="px-4 py-2.5 text-left font-normal">
        <span className="block">{rule.ruleId}</span>
        {rule.evidence.length > 0 ? (
          <details className="mt-1">
            <summary className="cursor-pointer text-[11px] text-info">
              {rule.evidence.length === 1 ? '1 fact' : `${rule.evidence.length} facts`}
            </summary>
            <ul className="mt-1.5 flex flex-col gap-1.5" aria-label={`Evidence for ${rule.ruleId}`}>
              {rule.evidence.map((e, i) => (
                <EvidenceItem key={i} evidence={e} />
              ))}
            </ul>
          </details>
        ) : null}
      </th>
      <td className="px-2 py-2.5 text-text-secondary">{value(rule.observed)}</td>
      <td className="px-2 py-2.5 text-text-secondary">{value(rule.threshold)}</td>
      <td className="px-2 py-2.5">
        <QualityBadge outcome={rule.outcome} size="dense" label="Rule outcome" />
      </td>
      <td className="px-4 py-2.5 text-text-secondary">{rule.blocking ? 'yes' : 'no'}</td>
    </tr>
  );
}

function EvidenceItem({ evidence }: { evidence: QualityRuleEvidence }) {
  const subject = [evidence.caseKey, evidence.engine ?? (evidence.caseKey ? null : evidence.target)]
    .filter(Boolean)
    .join(' · ');
  return (
    <li className="flex flex-wrap items-center gap-2 font-sans text-body-sm text-text-secondary">
      {evidence.status === 'QUARANTINED_FAIL' ? (
        <QuarantinedChip />
      ) : evidence.status ? (
        <span className="font-mono text-mono-sm text-text">{evidence.status}</span>
      ) : null}
      {subject ? <span className="font-mono text-mono-sm text-text">{subject}</span> : null}
      <span>{evidence.detail}</span>
      {!evidence.counted && evidence.status !== null ? (
        <span className="text-text-muted">(not counted)</span>
      ) : null}
    </li>
  );
}
