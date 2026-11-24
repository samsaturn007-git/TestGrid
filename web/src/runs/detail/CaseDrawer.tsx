import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { freshArtifactLink, useArtifactLink, useArtifactText } from '../../api/queries.ts';
import type {
  ArtifactState,
  ArtifactView,
  AttemptResults,
  CaseResult,
  RunTarget,
} from '../../api/types.ts';
import { iconButton, secondaryButton } from '../../components/buttons.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { formatAbsolute, formatDuration } from '../../lib/time.ts';

// PRD-06 FR-8: everything about one case in one attempt. A modal dialog: focus moves in on open,
// Tab stays inside, Esc or the backdrop closes it, and the opener gets focus back (the page
// keeps the opener). Bytes are only ever read through short-lived signed links (FR-4).

const STATE_CHIP: Record<ArtifactState, string> = {
  AVAILABLE: 'border-info-rim bg-info-tint text-info',
  EXPIRED: 'border-neutral-rim bg-neutral-tint text-neutral',
  PURGED: 'border-neutral-rim bg-neutral-tint text-neutral',
  MISSING: 'border-warning-rim bg-warning-tint text-warning',
};

function StateBadge({ artifact }: { artifact: ArtifactView | undefined }) {
  const label = artifact ? artifact.state : 'NONE';
  const chip = artifact
    ? STATE_CHIP[artifact.state]
    : 'border-hairline-strong bg-canvas text-text-muted';
  return (
    <span
      className={`rounded-[4px] border px-1.5 py-0.5 font-mono text-[11px] font-medium ${chip}`}
    >
      <span className="sr-only">State: </span>
      {label}
    </span>
  );
}

/** What to say instead of a broken image or an empty box. */
function Unavailable({ artifact, what }: { artifact: ArtifactView | undefined; what: string }) {
  let text: string;
  if (!artifact) {
    text =
      what === 'log tail'
        ? 'No log tail was kept for this attempt.'
        : `No ${what} was kept for this case. Passing cases keep none, and a ${what} holding a secret value is withheld.`;
  } else if (artifact.state === 'MISSING') {
    text = `The ${what} file is missing from storage. The case result and error message are kept.`;
  } else {
    text = `This ${what} was deleted after its retention period (it expired ${formatAbsolute(artifact.expiresAt)}). The case result and error message are kept.`;
  }
  return (
    <p
      role="status"
      className="rounded-card border border-hairline-strong bg-surface px-3 py-2.5 text-body-sm text-text-secondary"
    >
      {text}
    </p>
  );
}

function Block({
  title,
  artifact,
  children,
}: {
  title: string;
  artifact: ArtifactView | undefined;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <h4
          id={id}
          className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted"
        >
          {title}
        </h4>
        <StateBadge artifact={artifact} />
      </div>
      {children}
    </section>
  );
}

function Screenshot({ artifact, alt }: { artifact: ArtifactView; alt: string }) {
  const link = useArtifactLink(artifact.id);
  const [broken, setBroken] = useState(false);
  if (link.error)
    return (
      <p role="alert" className="text-body-sm text-danger">
        Could not get a link to the screenshot.
      </p>
    );
  if (!link.data) return <p className="text-body-sm text-text-muted">Loading screenshot…</p>;
  if (broken) {
    return (
      <p
        role="status"
        className="flex flex-wrap items-center gap-2 text-body-sm text-text-secondary"
      >
        The screenshot could not be loaded; its link may have expired.
        <button
          type="button"
          className={secondaryButton}
          onClick={() => {
            setBroken(false);
            void link.refetch();
          }}
        >
          Try again
        </button>
      </p>
    );
  }
  return (
    <img
      src={link.data.url}
      alt={alt}
      onError={() => setBroken(true)}
      className="max-h-72 w-full rounded-card border border-hairline object-contain object-top"
    />
  );
}

function Trace({ artifact }: { artifact: ArtifactView }) {
  const [state, setState] = useState<'idle' | 'busy' | 'failed'>('idle');
  const [copied, setCopied] = useState(false);
  const command = `npx playwright show-trace trace-${artifact.id}.zip`;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className={secondaryButton}
          aria-busy={state === 'busy'}
          onClick={() => {
            setState('busy');
            freshArtifactLink(artifact.id).then(
              (link) => {
                setState('idle');
                // Content-Disposition: attachment, so the page stays and the file downloads.
                window.location.assign(link.url);
              },
              () => setState('failed'),
            );
          }}
        >
          Download trace ({formatBytes(artifact.byteSize)})
        </button>
      </div>
      <div className="flex items-start gap-2">
        <code className="min-w-0 flex-1 break-all rounded-chip border border-hairline bg-canvas px-2.5 py-2 font-mono text-mono-sm">
          {command}
        </code>
        <button
          type="button"
          className={secondaryButton}
          onClick={() => {
            void navigator.clipboard?.writeText(command).then(() => setCopied(true));
          }}
        >
          {copied ? 'Copied' : 'Copy command'}
        </button>
      </div>
      <p className="text-body-sm text-text-muted">
        Run the command where the file was saved; the trace viewer steps through every action, its
        DOM snapshot and the network.
      </p>
      {state === 'failed' ? (
        <p role="alert" className="text-body-sm text-danger">
          Could not get a download link. The trace may have expired; reopen the case to check.
        </p>
      ) : null}
    </div>
  );
}

function LogTail({ artifact }: { artifact: ArtifactView }) {
  const text = useArtifactText(artifact.id);
  const pre = useRef<HTMLPreElement>(null);
  useEffect(() => {
    // The interesting part of a tail is its end.
    if (pre.current) pre.current.scrollTop = pre.current.scrollHeight;
  }, [text.data]);
  if (text.error)
    return (
      <p role="alert" className="text-body-sm text-danger">
        Could not load the log tail.
      </p>
    );
  if (text.data === undefined)
    return <p className="text-body-sm text-text-muted">Loading log tail…</p>;
  return (
    <pre
      ref={pre}
      tabIndex={0}
      aria-label="Log tail, redacted"
      className="max-h-64 overflow-auto rounded-card border border-hairline bg-canvas p-3 font-mono text-mono-sm whitespace-pre-wrap"
    >
      {text.data}
    </pre>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const FOCUSABLE = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface DrawerCase {
  suiteKey: string;
  target: RunTarget;
  attempt: AttemptResults;
  result: CaseResult;
}

export function CaseDrawer({ value, onClose }: { value: DrawerCase; onClose: () => void }) {
  const { suiteKey, target, attempt, result } = value;
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  useEffect(() => close.current?.focus(), []);

  const find = (kind: ArtifactView['kind']) =>
    attempt.artifacts.find(
      (a) => a.kind === kind && (kind === 'LOG' || a.caseKey === result.caseKey),
    );
  const screenshot = find('SCREENSHOT');
  const trace = find('TRACE');
  const log = find('LOG');

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !panel.current) return;
    const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    const first = items[0];
    const last = items.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  };

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div aria-hidden="true" className="absolute inset-0 bg-scrim" onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
        className="relative flex h-full w-full max-w-[560px] flex-col border-l border-hairline-strong bg-raised shadow-2xl"
      >
        <div className="flex items-center gap-2.5 border-b border-hairline px-4 py-3">
          <StatusBadge kind="case" status={result.status} />
          <h3 id={titleId} className="flex-1 truncate font-mono text-mono font-medium">
            {result.caseKey} <span className="text-text-muted">· {target.engine}</span>
          </h3>
          <button ref={close} type="button" className={iconButton} onClick={onClose}>
            <span className="font-mono text-[11px]">Esc</span>
            <span className="sr-only"> · close case details</span>
          </button>
        </div>
        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4 py-4">
          <dl className="flex flex-wrap gap-x-4 gap-y-2 text-body-sm text-text-muted">
            <div className="flex items-center gap-1.5">
              <dt>error class</dt>
              <dd className="font-mono text-text">{result.errorClass ?? 'none'}</dd>
            </div>
            <div className="flex items-center gap-1.5">
              <dt>attempt</dt>
              <dd className="font-mono text-text">
                {attempt.attempt.attemptNo} of {target.maxAttempts}
              </dd>
            </div>
            <div className="flex items-center gap-1.5">
              <dt>duration</dt>
              <dd className="font-mono text-text">{formatDuration(result.durationMs)}</dd>
            </div>
            <div className="flex items-center gap-1.5">
              <dt>retries</dt>
              <dd className="font-mono text-text">{result.retryCount}</dd>
            </div>
          </dl>
          <Link
            to={`/cases/${encodeURIComponent(result.caseKey)}?suiteKey=${encodeURIComponent(suiteKey)}`}
            className="self-start text-body-sm"
          >
            Case history and health for {result.caseKey}
          </Link>
          <section className="flex flex-col gap-1.5" aria-label="Error message">
            <h4 className="font-mono text-[11px] font-medium uppercase tracking-[0.06em] text-text-muted">
              Error · redacted before storage
            </h4>
            {result.errorMessage ? (
              <pre className="rounded-card border border-danger-rim bg-danger-tint p-3 font-mono text-mono-sm whitespace-pre-wrap text-text">
                {result.errorMessage}
              </pre>
            ) : (
              <p className="text-body-sm text-text-muted">No error message for this case.</p>
            )}
          </section>
          <Block title="Screenshot" artifact={screenshot}>
            {screenshot?.state === 'AVAILABLE' ? (
              <Screenshot
                artifact={screenshot}
                alt={`Screenshot of ${result.caseKey} on ${target.engine} when it failed`}
              />
            ) : (
              <Unavailable artifact={screenshot} what="screenshot" />
            )}
          </Block>
          <Block title="Trace" artifact={trace}>
            {trace?.state === 'AVAILABLE' ? (
              <Trace artifact={trace} />
            ) : (
              <Unavailable artifact={trace} what="trace" />
            )}
          </Block>
          <Block title="Log tail" artifact={log}>
            {log?.state === 'AVAILABLE' ? (
              <LogTail artifact={log} />
            ) : (
              <Unavailable artifact={log} what="log tail" />
            )}
          </Block>
        </div>
      </div>
    </div>
  );
}
