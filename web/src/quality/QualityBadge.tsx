import tokens from '../../../docs/design/tokens.json' with { type: 'json' };
import type { QualityOutcome } from '../api/types.ts';
import { ROLE_CLASSES, type StatusRole } from '../components/status.ts';

// Contracts §2.4 outcomes, in the dashboard's status language: PASS like PASSED, FAIL like FAILED,
// INDETERMINATE like INFRA_ERROR's violet (the design's "couldn't decide" colour), with a question
// mark so colour is never the only signal.
const SPEC: Record<QualityOutcome, { role: StatusRole; icon: keyof typeof tokens.icons }> = {
  PASS: { role: 'success', icon: 'check' },
  FAIL: { role: 'danger', icon: 'cross' },
  INDETERMINATE: { role: 'infra', icon: 'quest' },
};

export const EXIT_CODE: Record<QualityOutcome, number> = { PASS: 0, FAIL: 1, INDETERMINATE: 2 };

export function QualityBadge({
  outcome,
  size = 'chip',
  label = 'Quality gate',
}: {
  outcome: QualityOutcome;
  size?: 'chip' | 'dense' | 'large';
  /** What the outcome is of, for screen readers: the gate, or one rule. */
  label?: string;
}) {
  const { role, icon } = SPEC[outcome];
  const classes = ROLE_CLASSES[role];
  const dense = size === 'dense';
  const iconSize = size === 'large' ? 15 : dense ? 12 : 14;
  return (
    <span
      role="img"
      aria-label={`${label}: ${outcome}`}
      data-outcome={outcome}
      className={[
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap font-mono',
        dense
          ? 'h-5 text-mono-sm text-text'
          : `rounded-chip border pl-[7px] pr-[9px] ${classes.chip} ${size === 'large' ? 'h-[30px] text-[14px] font-semibold' : 'h-6 text-mono-sm'}`,
      ].join(' ')}
    >
      <svg
        width={iconSize}
        height={iconSize}
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth={dense ? 1.8 : 1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={dense ? classes.icon : ''}
      >
        <path d={tokens.icons[icon]} />
      </svg>
      {outcome}
    </span>
  );
}

/** The grey chip a failure gets when a quarantine excused it. */
export function QuarantinedChip() {
  return (
    <span className="inline-flex h-[22px] shrink-0 items-center rounded-chip border border-neutral-rim bg-neutral-tint px-[7px] font-mono text-mono-sm font-medium text-text-secondary">
      QUARANTINED_FAIL
    </span>
  );
}
