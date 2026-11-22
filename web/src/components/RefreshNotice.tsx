import { formatAbsolute } from '../lib/time.ts';
import { secondaryButton } from './buttons.ts';

/**
 * "Updated 12:04:30 UTC" while refreshes succeed; an alert with the age of what is shown when
 * one fails, so old numbers never pass for current ones (FR-7).
 */
export function RefreshNotice({
  updatedAt,
  failed,
  onRetry,
}: {
  updatedAt: number;
  failed: boolean;
  onRetry: () => void;
}) {
  if (updatedAt === 0) return null;
  const iso = new Date(updatedAt).toISOString();
  if (failed) {
    return (
      <p
        role="alert"
        className="flex flex-wrap items-center gap-3 rounded-card border border-warning-rim bg-warning-tint px-3 py-2 text-body"
      >
        <span>
          Could not refresh. What you see was loaded at{' '}
          <time dateTime={iso}>{formatAbsolute(iso)}</time>.
        </span>
        <button type="button" className={secondaryButton} onClick={onRetry}>
          Try again
        </button>
      </p>
    );
  }
  return (
    <span className="text-body-sm text-text-muted">
      Updated <time dateTime={iso}>{formatAbsolute(iso)}</time>
    </span>
  );
}
