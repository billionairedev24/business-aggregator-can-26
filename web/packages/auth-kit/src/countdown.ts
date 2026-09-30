import { useEffect, useState } from 'react';

/** Seconds left until `deadline` (epoch ms), ticking once a second; 0 when passed. */
export function useCountdown(deadline: number): number {
  const left = () => Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
  const [seconds, setSeconds] = useState(left);
  useEffect(() => {
    setSeconds(left());
    const id = window.setInterval(() => {
      const s = left();
      setSeconds(s);
      if (s === 0) window.clearInterval(id);
    }, 1000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deadline]);
  return seconds;
}

/** 42 → "0:42" */
export const mmss = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
