import { useEffect, useState } from 'react';

export type AsyncState<T> =
  { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; data: T };

/** Loads once per `key`. Stale results from an earlier key are dropped. */
export function useAsync<T>(load: () => Promise<T>, key: string): AsyncState<T> {
  const [state, setState] = useState<{ key: string; value: AsyncState<T> }>({
    key,
    value: { status: 'loading' },
  });
  useEffect(() => {
    let active = true;
    load().then(
      (data) => {
        if (active) setState({ key, value: { status: 'ready', data } });
      },
      (err: unknown) => {
        const message = err instanceof Error ? err.message : 'Something went wrong';
        if (active) setState({ key, value: { status: 'error', message } });
      },
    );
    return () => {
      active = false;
    };
    // `load` is recreated every render; `key` is what identifies the request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return state.key === key ? state.value : { status: 'loading' };
}
