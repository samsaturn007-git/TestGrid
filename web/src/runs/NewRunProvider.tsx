import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { NewRunContext } from './newRunContext.ts';
import { NewRunDialog } from './NewRunDialog.tsx';

/** Owns whether the dialog is open, and gives focus back to whatever opened it. */
export function NewRunProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState<{ suiteKey: string | undefined; n: number } | null>(null);
  const opener = useRef<HTMLElement | null>(null);
  const count = useRef(0);

  const openNewRun = useCallback((suiteKey?: string) => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    count.current += 1;
    setOpen({ suiteKey, n: count.current });
  }, []);

  // `navigating`: a route change follows and the shell moves focus to <main>, so the opener
  // must not take it back.
  const close = useCallback((options?: { navigating?: boolean }) => {
    setOpen(null);
    const target = opener.current;
    opener.current = null;
    if (options?.navigating) return;
    queueMicrotask(() => {
      if (target?.isConnected) target.focus();
    });
  }, []);

  const controls = useMemo(() => ({ openNewRun }), [openNewRun]);

  return (
    <NewRunContext value={controls}>
      {children}
      {open ? (
        // A fresh key per opening, so every dialog starts from clean form state.
        <NewRunDialog key={open.n} initialSuiteKey={open.suiteKey} onClose={close} />
      ) : null}
    </NewRunContext>
  );
}
