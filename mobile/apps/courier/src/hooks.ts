import NetInfo from '@react-native-community/netinfo';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';

import { marketZone, type Courier, type Run, type Shift } from './api/courier';
import { RUN_REFRESH_MS } from './config';
import { useI18n } from './i18n';
import { withPending, type OutboxState } from './offline/outbox';
import { ME_KEY, RUN_KEY, SHIFTS_KEY, services } from './services';

export function useMe() {
  return useQuery<Courier>({ queryKey: ME_KEY, queryFn: () => services().courier.me(), refetchInterval: RUN_REFRESH_MS });
}

export function useShifts() {
  return useQuery<Shift[]>({ queryKey: SHIFTS_KEY, queryFn: () => services().courier.shifts() });
}

export function useOutbox(): OutboxState {
  const outbox = services().outbox;
  return useSyncExternalStore(
    (cb) => outbox.subscribe(cb),
    () => outbox.snapshot,
    () => outbox.snapshot,
  );
}

/** The open run with the actions still on the phone applied (and which stops have them). */
export function useRun() {
  const query = useQuery<Run | null>({ queryKey: RUN_KEY, queryFn: () => services().courier.run(), refetchInterval: RUN_REFRESH_MS });
  const { pending } = useOutbox();
  const projected = useMemo(() => withPending(query.data, pending), [query.data, pending]);
  return { ...query, run: projected.run, unsent: projected.unsent };
}

/** The run's market zone (region model, S-134); times show in it. Undefined until known: the device's zone then. */
export function useMarketZone(market: string | null | undefined): string | undefined {
  const { locale } = useI18n();
  const regions = useQuery({ queryKey: ['regions', locale], queryFn: () => services().courier.regions(locale), staleTime: 3600_000, retry: 0 });
  return marketZone(regions.data, market);
}

/** Connectivity; coming back online wakes the outbox. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  const was = useRef(true);
  useEffect(
    () =>
      NetInfo.addEventListener((s) => {
        const now = s.isConnected !== false && s.isInternetReachable !== false;
        setOnline(now);
        if (now && !was.current) services().outbox.wake();
        was.current = now;
      }),
    [],
  );
  return online;
}
