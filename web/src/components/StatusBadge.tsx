import type {
  AttemptStatus,
  CaseStatus,
  RunStatus,
  TargetStatus,
  WorkerState,
} from '../api/types.ts';
import { KIND_LABEL, resolveStatus, ROLE_CLASSES } from './status.ts';

type StatusProps =
  | { kind: 'run'; status: RunStatus }
  | { kind: 'target'; status: TargetStatus }
  | { kind: 'attempt'; status: AttemptStatus }
  | { kind: 'case'; status: CaseStatus }
  | { kind: 'worker'; status: WorkerState };

export type StatusBadgeProps = StatusProps & {
  /** `chip`: tint and rim (headers, lists). `dense`: icon and label only (tables, matrix). */
  size?: 'chip' | 'dense';
  /** False while live updates are paused, so a RUNNING icon doesn't look live when it isn't. */
  animate?: boolean;
};

/**
 * The one place a status becomes pixels (PRD-05 FR-5). The label is always the exact enum
 * value, and every badge has an icon and an aria-label, so colour is never the only signal.
 */
export function StatusBadge({ kind, status, size = 'chip', animate = true }: StatusBadgeProps) {
  const { role, iconPath, dashed, pulse } = resolveStatus(kind, status);
  const classes = ROLE_CLASSES[role];
  const chip = size === 'chip';
  const iconSize = chip ? 14 : 12;

  return (
    <span
      role="img"
      aria-label={`${KIND_LABEL[kind]}: ${status}`}
      data-status={status}
      data-role={role}
      className={[
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap font-mono text-mono-sm',
        'transition-colors duration-(--tg-dur-status)',
        chip ? `h-6 rounded-chip border pl-[7px] pr-[9px] ${classes.chip}` : 'h-5 text-text',
      ].join(' ')}
    >
      <svg
        width={iconSize}
        height={iconSize}
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth={chip ? 1.6 : 1.8}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={[chip ? '' : classes.icon, pulse && animate ? 'tg-pulse' : ''].join(' ').trim()}
      >
        <path d={iconPath} {...(dashed ? { strokeDasharray: '1.4 2.1' } : {})} />
      </svg>
      {status}
    </span>
  );
}
