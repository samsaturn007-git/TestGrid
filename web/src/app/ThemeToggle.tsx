import { useState } from 'react';
import { effectiveTheme, saveTheme, type Theme } from './theme.ts';

const MOON = 'M13 9.5A5.5 5.5 0 0 1 6.5 3a5.5 5.5 0 1 0 6.5 6.5z';
const SUN =
  'M8 5a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1';

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(effectiveTheme);
  const next: Theme = theme === 'dark' ? 'light' : 'dark';

  return (
    <button
      type="button"
      aria-label={`Switch to ${next} theme`}
      onClick={() => {
        saveTheme(next);
        setTheme(next);
      }}
      className="inline-flex size-[34px] items-center justify-center rounded-chip border border-hairline bg-surface text-text-secondary hover:border-hairline-strong hover:text-text"
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={theme === 'dark' ? MOON : SUN} />
      </svg>
    </button>
  );
}
