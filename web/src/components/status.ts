import tokens from '../../../docs/design/tokens.json' with { type: 'json' };
import type {
  AttemptStatus,
  CaseStatus,
  RunStatus,
  TargetStatus,
  WorkerState,
} from '../api/types.ts';

export type StatusKind = 'run' | 'target' | 'attempt' | 'case' | 'worker';
export type StatusRole =
  'neutral' | 'info' | 'success' | 'danger' | 'warning' | 'infra' | 'abandoned' | 'flaky';

interface StatusSpec {
  role: string;
  icon: string;
  dashed?: boolean;
  pulse?: boolean;
}

// The status → role/icon mapping lives in tokens.json, next to the design it came from. Typing
// each table as a Record over the generated enum makes the build fail if the API gains a value
// the design has no badge for.
const lifecycle: Record<RunStatus | TargetStatus | AttemptStatus, StatusSpec> =
  tokens.statusMap.run_target_attempt;
const caseStatus: Record<CaseStatus, StatusSpec> = tokens.statusMap.case;
const workerState: Record<WorkerState, StatusSpec> = tokens.statusMap.workerState;

const TABLES: Record<StatusKind, Record<string, StatusSpec>> = {
  run: lifecycle,
  target: lifecycle,
  attempt: lifecycle,
  case: caseStatus,
  worker: workerState,
};

export const KIND_LABEL: Record<StatusKind, string> = {
  run: 'Run status',
  target: 'Target status',
  attempt: 'Attempt status',
  case: 'Case status',
  worker: 'Worker state',
};

// Literal class names, so Tailwind's scanner generates every one of them.
export const ROLE_CLASSES: Record<StatusRole, { chip: string; icon: string }> = {
  neutral: { chip: 'bg-neutral-tint border-neutral-rim text-neutral', icon: 'text-neutral' },
  info: { chip: 'bg-info-tint border-info-rim text-info', icon: 'text-info' },
  success: { chip: 'bg-success-tint border-success-rim text-success', icon: 'text-success' },
  danger: { chip: 'bg-danger-tint border-danger-rim text-danger', icon: 'text-danger' },
  warning: { chip: 'bg-warning-tint border-warning-rim text-warning', icon: 'text-warning' },
  infra: { chip: 'bg-infra-tint border-infra-rim text-infra', icon: 'text-infra' },
  abandoned: {
    chip: 'bg-abandoned-tint border-abandoned-rim text-abandoned',
    icon: 'text-abandoned',
  },
  flaky: { chip: 'bg-flaky-tint border-flaky-rim text-flaky', icon: 'text-flaky' },
};

const ICONS: Record<string, string> = tokens.icons;

export interface ResolvedStatus {
  role: StatusRole;
  iconPath: string;
  dashed: boolean;
  pulse: boolean;
}

function isRole(role: string): role is StatusRole {
  return Object.hasOwn(ROLE_CLASSES, role);
}

/** Throws on a token-file typo rather than rendering an unstyled or icon-less badge. */
export function resolveStatus(kind: StatusKind, status: string): ResolvedStatus {
  const spec = TABLES[kind][status];
  if (!spec) throw new Error(`no ${kind} badge for status ${status}`);
  const iconPath = ICONS[spec.icon];
  if (!isRole(spec.role) || !iconPath) {
    throw new Error(`bad badge spec for ${kind} ${status}: ${JSON.stringify(spec)}`);
  }
  return {
    role: spec.role,
    iconPath,
    dashed: spec.dashed === true,
    pulse: spec.pulse === true,
  };
}

/** The values each kind can show, in the order tokens.json lists them. */
export function statusValues(kind: StatusKind): string[] {
  return Object.keys(TABLES[kind]);
}
