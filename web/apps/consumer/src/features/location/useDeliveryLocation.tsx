import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import { useQueryClient } from '@tanstack/react-query';
import { marketsQuery } from './api';
import { BookAddress, Reverse, resolveLocation, SavedLocation, SAVED_KEY, type DeliveryLocation } from './sources';

export { SAVED_KEY, SavedLocation, type DeliveryLocation } from './sources';

/**
 * The location every screen reads (consumer location, rebuilt 2026-10-19; the sources and their order are in
 * sources.ts). Server rendering and the first browser render agree on "locating"; the browser then reads, without
 * prompting anyone, the saved choice, the signed-in person's default address, the CDN's city and the fallback market.
 * `chooser` opens the location dialog (<LocationChooser>, in the layout); `save` is what it calls with the place chosen
 * — every screen reading the location follows at once, without leaving the page.
 */
interface LocationContextValue {
  location: DeliveryLocation;
  save: (l: SavedLocation) => void;
  forget: () => void;
  chooser: { open: boolean; show: () => void; hide: () => void };
  /** The device's position, named by the api (null outside every market). Used by "Use my current location". */
  reverse: (lat: number, lng: number) => Promise<z.infer<typeof Reverse> | null>;
}
const LocationContext = createContext<LocationContextValue | null>(null);

function readSaved(): SavedLocation | null {
  try { const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(SAVED_KEY); return raw ? SavedLocation.parse(JSON.parse(raw)) : null; } catch { return null; }
}

/** `#location` in the address opens the dialog (the old /location page redirects here, links can point at it). */
const HASH = '#location';

export interface DeliveryLocationProviderProps {
  /** The CDN's city for this visitor (session `location.city`), when known. */
  ipCity?: string | null;
  /** Signed in: the default address is a source. Undefined while the session loads. */
  signedIn?: boolean;
  children: ReactNode;
}

export function DeliveryLocationProvider({ ipCity, signedIn, children }: DeliveryLocationProviderProps) {
  const [location, setLocation] = useState<DeliveryLocation>({ status: 'locating' });
  const [open, setOpen] = useState(false);
  const [version, setVersion] = useState(0); // bumps on forget(): read the sources again
  const queryClient = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    const saved = readSaved();
    if (saved) { setLocation(resolveLocation({ saved, addresses: null, ipCity: null, fallback: null })); return; }
    if (signedIn === undefined) return; // the session is still loading: stay "locating" a moment
    void (async () => {
      const addresses = signedIn
        ? await queryClient.fetchQuery({ queryKey: ['me', 'addresses'], queryFn: async () => (await http('/api/v1/me/addresses', {}, z.object({ items: z.array(BookAddress) }))).items, staleTime: 60_000 }).catch(() => null)
        : null;
      const needFallback = !(addresses?.length) && !ipCity;
      const fallback = needFallback ? await queryClient.fetchQuery(marketsQuery).then(r => r.fallback ?? null, () => null) : null;
      if (!cancelled) setLocation(resolveLocation({ saved: null, addresses, ipCity, fallback }));
    })();
    return () => { cancelled = true; };
  }, [ipCity, signedIn, queryClient, version]);

  // `#location` opens the dialog, on arrival and whenever a link points at it
  useEffect(() => {
    const check = () => {
      if (window.location.hash !== HASH) return;
      setOpen(true);
      window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
    };
    check();
    window.addEventListener('hashchange', check);
    return () => window.removeEventListener('hashchange', check);
  }, []);

  const save = useCallback((l: SavedLocation) => {
    const clean = SavedLocation.parse(l);
    try { window.localStorage.setItem(SAVED_KEY, JSON.stringify(clean)); } catch { /* private mode: this visit only */ }
    setLocation({ status: 'saved', source: 'saved', ...clean });
  }, []);
  const forget = useCallback(() => {
    try { window.localStorage.removeItem(SAVED_KEY); } catch { /* ignore */ }
    setLocation({ status: 'locating' });
    setVersion(v => v + 1);
  }, []);
  const reverse = useCallback(async (lat: number, lng: number) => {
    try { return await http(`/api/v1/geo/reverse?lat=${lat.toFixed(5)}&lng=${lng.toFixed(5)}`, {}, Reverse); }
    catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
  }, []);
  const chooser = useMemo(() => ({ open, show: () => setOpen(true), hide: () => setOpen(false) }), [open]);
  const value = useMemo(() => ({ location, save, forget, chooser, reverse }), [location, save, forget, chooser, reverse]);
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>;
}

/**
 * S-116: the delivery location where a page may sit outside <DeliveryLocationProvider> (the sign-in and registration
 * pages): the provider's when there is one, else the location saved in this browser, else none.
 */
export function useKnownLocation(): DeliveryLocation | null {
  const v = useContext(LocationContext);
  if (v) return v.location;
  const saved = readSaved();
  return saved ? { status: 'saved', source: 'saved', ...saved } : null;
}

/** `{ location, save, forget, chooser, reverse }`. */
export function useDeliveryLocation(): LocationContextValue {
  const v = useContext(LocationContext);
  if (!v) throw new Error('useDeliveryLocation outside <DeliveryLocationProvider>');
  return v;
}
