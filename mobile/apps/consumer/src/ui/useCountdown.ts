import { useEffect, useState } from 'react';

/** Seconds until `at` (epoch ms), ticking every second; 0 once passed. */
export function useCountdown(at: number, now: () => number = Date.now): number {
  const left = () => Math.max(0, Math.ceil((at - now()) / 1000));
  const [state, setState] = useState(() => ({ at, seconds: left() }));
  // a new deadline (a code was sent again): start from it at once (derived state, set while rendering)
  if (state.at !== at) setState({ at, seconds: left() });
  useEffect(() => {
    const timer = setInterval(() => {
      const seconds = Math.max(0, Math.ceil((at - now()) / 1000));
      setState({ at, seconds });
      if (seconds <= 0) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [at, now]);
  return state.at === at ? state.seconds : left();
}
