import AsyncStorage from '@react-native-async-storage/async-storage';
import NetInfo from '@react-native-community/netinfo';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';

import { frenchFirst, marketZone, type Courier, type Run, type Shift } from './api/courier';
import { RUN_REFRESH_MS } from './config';
import { LANGUAGE_KEY, useI18n } from './i18n';
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

function useRegions() {
  const { locale } = useI18n();
  return useQuery({ queryKey: ['regions', locale], queryFn: () => services().courier.regions(locale), staleTime: 3600_000, retry: 0 });
}

/** The run's market zone (region model, S-134); times show in it. Undefined until known: the device's zone then. */
export function useMarketZone(market: string | null | undefined): string | undefined {
  return marketZone(useRegions().data, market);
}

/**
 * S-116 (Loi 96 readiness): a courier whose market is French-first (region configuration) gets the app in French
 * unless they picked a language in Account. Their pick always wins.
 */
export function useFrenchFirst(market: string | null | undefined) {
  const { locale, setLocale } = useI18n();
  const first = frenchFirst(useRegions().data, market);
  useEffect(() => {
    if (!first || locale === 'fr-CA') return;
    void AsyncStorage.getItem(LANGUAGE_KEY).then((picked) => {
      if (!picked) setLocale('fr-CA');
    });
    // once per market: the courier's own pick afterwards is kept
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [first]);
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
