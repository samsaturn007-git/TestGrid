import type { FailureCategory } from '../api/types.ts';
import { ROLE_CLASSES, type StatusRole } from '../components/status.ts';

// PRD-07 FR-7: a failure group's category as the design draws it (LaterPRD07). The label is
// always the exact enum value and there is always an icon, so colour is never the only signal.

const SPEC: Record<FailureCategory, { role: StatusRole; icon: string }> = {
  PRODUCT_SUSPECTED: {
    role: 'danger',
    icon: 'M2.8 5L8 2.5 13.2 5v6L8 13.5 2.8 11zM2.8 5L8 7.5l5.2-2.5M8 7.5v6',
  },
  TEST_BROKEN: { role: 'danger', icon: 'M6 4.5L2.5 8 6 11.5M10 4.5l3.5 3.5-3.5 3.5' },
  TIMEOUT: {
    role: 'warning',
    icon: 'M8 2.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11zM8 5v3.2l2 1.3',
  },
  ENVIRONMENT: {
    role: 'infra',
    icon: 'M6 1.8v2.7M10 1.8v2.7M4.6 4.5h6.8v2.6a3.4 3.4 0 0 1-6.8 0zM8 10.5v3.7',
  },
  AUTOMATION_FLAKE: { role: 'flaky', icon: 'M1.8 9.5l2.4-3 2.6 4 2.5-5 2.5 4 2.4-2.5' },
  UNKNOWN: { role: 'neutral', icon: 'M6 6a2 2 0 1 1 2.5 1.9c-.4.2-.5.5-.5.9V10M8 12.5h.01' },
};

export function FailureCategoryBadge({ category }: { category: FailureCategory }) {
  const spec = SPEC[category];
  return (
    <span
      role="img"
      aria-label={`Failure category: ${category}`}
      data-category={category}
      className={`inline-flex h-6 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-chip border pl-[7px] pr-[9px] font-mono text-mono-sm ${ROLE_CLASSES[spec.role].chip}`}
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={spec.icon} />
      </svg>
      {category}
    </span>
  );
}

export const CATEGORIES: FailureCategory[] = [
  'PRODUCT_SUSPECTED',
  'TEST_BROKEN',
  'AUTOMATION_FLAKE',
  'TIMEOUT',
  'ENVIRONMENT',
  'UNKNOWN',
];
