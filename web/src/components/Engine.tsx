import type { Engine } from '../api/types.ts';

const MARK: Record<Engine, string> = { chromium: 'C', firefox: 'F', webkit: 'W' };

/** The one-letter engine mark. Decorative: the engine name always sits next to it. */
export function EngineMark({ engine }: { engine: Engine }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex size-4 items-center justify-center rounded-tag border border-text-muted font-mono text-[9px] font-semibold leading-none"
    >
      {MARK[engine]}
    </span>
  );
}

export function EngineChip({ engine }: { engine: Engine }) {
  return (
    <span className="inline-flex h-6 items-center gap-1.5 rounded-tag border border-hairline-strong bg-overlay pl-1 pr-2 font-mono text-mono-sm font-medium">
      <EngineMark engine={engine} />
      {engine}
    </span>
  );
}
