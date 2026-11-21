import { primaryButton } from '../components/buttons.ts';
import { useNewRun } from './newRunContext.ts';

export function NewRunButton() {
  const { openNewRun } = useNewRun();
  return (
    <button
      type="button"
      className={primaryButton}
      aria-haspopup="dialog"
      onClick={() => openNewRun()}
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <path d="M8 3.5v9M3.5 8h9" />
      </svg>
      New run
    </button>
  );
}
