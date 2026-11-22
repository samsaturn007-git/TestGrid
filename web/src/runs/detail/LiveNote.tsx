import type { LiveMode } from '../useRunEvents.ts';

/**
 * Says how an unfinished run's page is kept current (PRD-05 FR-3). A status region, so a
 * change (live → paused → live) is announced without moving focus.
 */
export function LiveNote({ mode }: { mode: LiveMode }) {
  if (mode === 'polling') {
    return (
      <p
        role="status"
        className="flex items-center gap-2 rounded-card border border-warning-rim bg-warning-tint px-3 py-2 text-body"
      >
        <span aria-hidden="true" className="size-2 rounded-full bg-warning" />
        Live updates paused, refreshing every 5s
      </p>
    );
  }
  return (
    <p role="status" className="flex items-center gap-2 text-body-sm text-text-muted">
      <span
        aria-hidden="true"
        className={`size-2 rounded-full ${mode === 'live' ? 'bg-success' : 'bg-neutral'}`}
      />
      {mode === 'live' ? 'Live' : 'Connecting to live updates…'}
    </p>
  );
}
