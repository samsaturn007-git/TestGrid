import { createContext, useContext } from 'react';

export interface NewRunControls {
  /** Opens the new-run dialog, with `suiteKey` preselected when given. */
  openNewRun: (suiteKey?: string) => void;
}

export const NewRunContext = createContext<NewRunControls | null>(null);

export function useNewRun(): NewRunControls {
  const controls = useContext(NewRunContext);
  if (!controls) throw new Error('useNewRun needs a NewRunProvider (the app shell provides one)');
  return controls;
}
