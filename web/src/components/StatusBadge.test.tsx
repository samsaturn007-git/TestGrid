import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type {
  AttemptStatus,
  CaseStatus,
  RunStatus,
  TargetStatus,
  WorkerState,
} from '../api/types.ts';
import { expectNoSeriousA11yViolations } from '../test/axe.ts';
import { resolveStatus, statusValues, type StatusKind } from './status.ts';
import { StatusBadge, type StatusBadgeProps } from './StatusBadge.tsx';

// Written out from contracts §2.1 rather than read from tokens.json, so the test checks the
// token file instead of agreeing with it. Record<Enum, true> makes the compiler reject a
// missing or misspelt value.
const RUN: Record<RunStatus, true> = {
  QUEUED: true,
  RUNNING: true,
  PASSED: true,
  FAILED: true,
  TIMED_OUT: true,
  INFRA_ERROR: true,
  CANCELLED: true,
};
const TARGET: Record<TargetStatus, true> = { ...RUN, CLAIMED: true };
const ATTEMPT: Record<AttemptStatus, true> = {
  CLAIMED: true,
  RUNNING: true,
  PASSED: true,
  FAILED: true,
  TIMED_OUT: true,
  INFRA_ERROR: true,
  CANCELLED: true,
  ABANDONED: true,
};
const CASE: Record<CaseStatus, true> = {
  PASSED: true,
  FAILED: true,
  TIMED_OUT: true,
  SKIPPED: true,
  FLAKY: true,
};
const WORKER: Record<WorkerState, true> = { ACTIVE: true, DRAINING: true, LOST: true };

const ENUMS: [StatusKind, string[]][] = [
  ['run', Object.keys(RUN)],
  ['target', Object.keys(TARGET)],
  ['attempt', Object.keys(ATTEMPT)],
  ['case', Object.keys(CASE)],
  ['worker', Object.keys(WORKER)],
];

const KIND_NAME: Record<StatusKind, string> = {
  run: 'Run status',
  target: 'Target status',
  attempt: 'Attempt status',
  case: 'Case status',
  worker: 'Worker state',
};

function badge(kind: StatusKind, status: string, extra: Partial<StatusBadgeProps> = {}) {
  const props = { kind, status, ...extra } as StatusBadgeProps;
  return <StatusBadge {...props} />;
}

describe('StatusBadge (PRD-05 FR-5, AC-2)', () => {
  describe.each(ENUMS)('%s', (kind, values) => {
    it.each(values)('%s renders its exact enum label, an icon and an aria-label', (status) => {
      const { container } = render(badge(kind, status));
      const el = screen.getByRole('img', { name: `${KIND_NAME[kind]}: ${status}` });

      expect(el).toHaveTextContent(new RegExp(`^${status}$`));
      expect(el).toHaveAttribute('data-status', status);
      const path = container.querySelector('svg[aria-hidden="true"] path');
      expect(path?.getAttribute('d')).toBeTruthy();
    });

    it('every enum value has a badge', () => {
      expect(statusValues(kind)).toEqual(expect.arrayContaining(values));
    });
  });

  it('badge tables hold no value outside the enums that use them', () => {
    // Run, target and attempt share one lifecycle table (tokens.json run_target_attempt).
    const lifecycle = new Set([
      ...Object.keys(RUN),
      ...Object.keys(TARGET),
      ...Object.keys(ATTEMPT),
    ]);
    expect([...statusValues('run')].sort()).toEqual([...lifecycle].sort());
    expect([...statusValues('case')].sort()).toEqual(Object.keys(CASE).sort());
    expect([...statusValues('worker')].sort()).toEqual(Object.keys(WORKER).sort());
  });

  it('tells the five terminal outcomes apart without colour: distinct icon and label', () => {
    const terminal: RunStatus[] = ['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'];
    const icons = terminal.map((s) => resolveStatus('run', s).iconPath);
    expect(new Set(icons).size).toBe(terminal.length);
  });

  it('tells the in-progress states apart from each other and from the outcomes', () => {
    const states: TargetStatus[] = ['QUEUED', 'CLAIMED', 'RUNNING'];
    // QUEUED and CLAIMED share the ring; CLAIMED's is dashed, so the shapes still differ.
    const shapes = states.map((s) => {
      const r = resolveStatus('target', s);
      return `${r.iconPath}|${r.dashed}`;
    });
    expect(new Set(shapes).size).toBe(states.length);
    const outcomeIcons = new Set(
      (['PASSED', 'FAILED', 'TIMED_OUT', 'INFRA_ERROR', 'CANCELLED'] as const).map(
        (s) => resolveStatus('run', s).iconPath,
      ),
    );
    for (const s of states)
      expect(outcomeIcons.has(resolveStatus('target', s).iconPath)).toBe(false);
  });

  it('never shows infrastructure trouble as a product failure (infra is violet, not red)', () => {
    expect(resolveStatus('run', 'INFRA_ERROR').role).toBe('infra');
    expect(resolveStatus('attempt', 'ABANDONED').role).toBe('abandoned');
    expect(resolveStatus('run', 'FAILED').role).toBe('danger');
    expect(resolveStatus('case', 'FAILED').role).toBe('danger');
  });

  it('pulses only the RUNNING icon, and stops when animation is off (live updates paused)', () => {
    const { container, rerender } = render(<StatusBadge kind="target" status="RUNNING" />);
    expect(container.querySelector('svg')).toHaveClass('tg-pulse');

    rerender(<StatusBadge kind="target" status="RUNNING" animate={false} />);
    expect(container.querySelector('svg')).not.toHaveClass('tg-pulse');

    rerender(<StatusBadge kind="target" status="CLAIMED" />);
    expect(container.querySelector('svg')).not.toHaveClass('tg-pulse');
  });

  it('dashes the CLAIMED ring so it differs from QUEUED in shape', () => {
    const { container } = render(<StatusBadge kind="target" status="CLAIMED" />);
    expect(container.querySelector('path')).toHaveAttribute('stroke-dasharray', '1.4 2.1');
  });

  it('dense size keeps the icon colour and the same label and name', () => {
    render(<StatusBadge kind="case" status="FLAKY" size="dense" />);
    const el = screen.getByRole('img', { name: 'Case status: FLAKY' });
    expect(el).toHaveTextContent('FLAKY');
    expect(el.querySelector('svg')).toHaveClass('text-flaky');
  });

  it('rejects a status the badge table does not know', () => {
    expect(() => resolveStatus('run', 'GREEN')).toThrow(/no run badge for status GREEN/);
  });

  it('a sheet of every badge in both sizes has no serious or critical axe violations', async () => {
    const { container } = render(
      <ul>
        {ENUMS.flatMap(([kind, values]) =>
          values.flatMap((status) =>
            (['chip', 'dense'] as const).map((size) => (
              <li key={`${kind}-${status}-${size}`}>{badge(kind, status, { size })}</li>
            )),
          ),
        )}
      </ul>,
    );
    await expectNoSeriousA11yViolations(container);
  });
});
