import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouterState } from '@tanstack/react-router';
import { useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { landingQuery, type Landing, type Run } from './api';

/**
 * The next pooled run where the visitor is (E-15: the delivery cut-off strip, the results page's "arrives tonight"
 * note and the home hero all say it): the Shop landing's `run` for the visitor's city, read once the browser knows
 * the place (the same query the Shop landing renders, so it is often in the cache already). `run` is null when the
 * market isn't served or has no run; undefined while it loads. A market the page was linked to (a Shop page's
 * `?market=`) wins over the visitor's own.
 */
export function useNextRun(): { landing: Landing | undefined; run: Run | null | undefined; market: string | undefined } {
  const { location } = useDeliveryLocation();
  const { locale } = useLocale();
  const explicit = useRouterState({ select: s => { const m = (s.location.search as { market?: unknown }).market; return typeof m === 'string' && m ? m : undefined; } });
  const known = explicit !== undefined || location.status !== 'locating';
  const landing = useQuery({ ...landingQuery(explicit ?? (known ? location.city : undefined), locale), enabled: known });
  const data = landing.data;
  return { landing: data, run: data ? (data.served ? data.run ?? null : null) : landing.isError ? null : undefined, market: data?.market };
}

/**
 * The time now, for a countdown: null on the server and in the first browser render (the two must match), then the
 * browser's clock, ticking every `everyMs`.
 */
export function useNow(everyMs = 30_000): number | null {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(id);
  }, [everyMs]);
  return now;
}

/** Whole minutes from `now` until `iso` (0 or less once it has passed). */
export const minutesUntil = (iso: string, now: number) => Math.floor((new Date(iso).getTime() - now) / 60_000);
