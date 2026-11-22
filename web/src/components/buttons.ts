// Literal class strings, so Tailwind's scanner sees every one.
const base =
  'inline-flex h-[34px] shrink-0 items-center justify-center gap-2 rounded-chip border px-3.5 text-body font-medium no-underline disabled:cursor-not-allowed aria-disabled:cursor-not-allowed';

export const primaryButton = `${base} border-primary-bg bg-primary-bg font-semibold text-primary-fg hover:opacity-90 aria-busy:opacity-80`;
export const secondaryButton = `${base} border-hairline-strong bg-raised text-text hover:border-text-muted disabled:text-text-muted`;
export const iconButton =
  'inline-flex size-[30px] items-center justify-center rounded-chip border border-transparent text-text-secondary hover:border-hairline-strong';
