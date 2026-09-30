import { useCallback, useState } from 'react';
import { Alert } from '@northline/ui';
import { rateLimitedFor } from './errors';
import { useAuthKitT } from './messages';
import { mmss, useCountdown } from './countdown';

/**
 * 429 `rate_limited` from northline-auth (S-9: too many codes, lookups or wrong factors per account, IP or session):
 * remembers until when, counts down, and lets the step disable its buttons meanwhile.
 */
export function useRateLimit() {
  const [until, setUntil] = useState(0);
  const left = useCountdown(until);
  /** true when `err` was a rate limit (now shown by <RateLimitNotice>); the caller then skips its own error. */
  const hold = useCallback((err: unknown) => {
    const seconds = rateLimitedFor(err);
    if (seconds === undefined) return false;
    setUntil(Date.now() + seconds * 1000);
    return true;
  }, []);
  const clear = useCallback(() => setUntil(0), []);
  return { left, limited: left > 0, hold, clear };
}

/** "Too many attempts. Try again in 4:59." — counts down, disappears at 0. */
export function RateLimitNotice({ left }: { left: number }) {
  const t = useAuthKitT();
  if (left <= 0) return null;
  return (
    <Alert tone="error" role="alert">
      <span aria-live="off">{t('rateLimited', { time: mmss(left) })}</span>
    </Alert>
  );
}
