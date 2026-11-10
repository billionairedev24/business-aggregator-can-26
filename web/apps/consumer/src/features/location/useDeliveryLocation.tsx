import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import { useQueryClient } from '@tanstack/react-query';
import { BookAddress, fromCookie, fromVisitor, PLACE_COOKIE, Reverse, resolveLocation, SavedLocation, SAVED_KEY, toCookie, type DeliveryLocation, type Visitor } from './sources';

export { SAVED_KEY, SavedLocation, type DeliveryLocation } from './sources';

/**
 * The place in a sentence ("Popular near Beltline", "pooled run to Kensington"): the delivery zone, else the chosen
 * neighbourhood, else the market's city — never a street address (a signed-in person's default address is one).
 */
export const shortPlace = (l: DeliveryLocation): string | undefined =>
  l.status === 'locating' ? undefined : l.zone ?? (l.street ? undefined : l.label?.split(',')[0]?.trim()) ?? l.city;

/**
 * The location every screen reads (consumer location, rebuilt 2026-10-19; the sources and their order are in
 * sources.ts). Server rendering and the first browser render agree (no hydration mismatch) on what the server knew
 * (S-192): the saved choice's cookie, else the place detected from the request (`visitor`), else "locating". The
 * browser then reads, without prompting anyone, the saved choice, the signed-in person's default address and the
 * detected place — else nothing is known (S-190: never a guessed city), and every page shows all markets.
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
  /** S-192: the place detected from the request (the footer names its country), whatever was chosen since. */
  visitor: Visitor | null;
}
const LocationContext = createContext<LocationContextValue | null>(null);

function readCookie(): SavedLocation | null {
  if (typeof document === 'undefined') return null;
  const hit = document.cookie.split('; ').find(c => c.startsWith(`${PLACE_COOKIE}=`));
  try { return hit ? fromCookie(decodeURIComponent(hit.slice(PLACE_COOKIE.length + 1))) : null; } catch { return null; }
}

/** The saved choice: this browser's storage, else (storage cleared or blocked) its cookie. */
function readSaved(): SavedLocation | null {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(SAVED_KEY);
    if (raw) return SavedLocation.parse(JSON.parse(raw));
  } catch { /* fall back to the cookie */ }
  return readCookie();
}

const YEAR = 60 * 60 * 24 * 365;
function writeCookie(l: SavedLocation | null) {
  if (typeof document === 'undefined') return;
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = l
    ? `${PLACE_COOKIE}=${encodeURIComponent(toCookie(l))}; Path=/; Max-Age=${YEAR}; SameSite=Lax${secure}`
    : `${PLACE_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax${secure}`;
}

/** What the server's render shows first, and so the browser's first render too (S-192). */
export function initialLocation(saved: SavedLocation | null | undefined, visitor: Visitor | null | undefined): DeliveryLocation {
  if (saved) return { status: 'saved', source: 'saved', ...saved };
  return fromVisitor(visitor) ?? { status: 'locating' };
}

/** `#location` in the address opens the dialog (the old /location page redirects here, links can point at it). */
const HASH = '#location';

export interface DeliveryLocationProviderProps {
  /** The CDN's city for this visitor (session `location.city`), when known. */
  ipCity?: string | null;
  /** S-192: the place detected from the request (`GET /api/v1/geo/visitor`, fetched during the server render). */
  visitor?: Visitor | null;
  /** S-192: the saved choice as the server read it from its cookie (the root loader's `place`). */
  initial?: SavedLocation | null;
  /** Signed in: the default address is a source. Undefined while the session loads. */
  signedIn?: boolean;
  children: ReactNode;
}

export function DeliveryLocationProvider({ ipCity, visitor = null, initial, signedIn, children }: DeliveryLocationProviderProps) {
  const [location, setLocation] = useState<DeliveryLocation>(() => initialLocation(initial, visitor));
  const [open, setOpen] = useState(false);
  const [version, setVersion] = useState(0); // bumps on forget(): read the sources again
  const queryClient = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    const saved = readSaved();
    if (saved) { setLocation(resolveLocation({ saved, addresses: null })); return; }
    if (signedIn === undefined) return; // the session is still loading: keep what the server showed a moment
    void (async () => {
      const addresses = signedIn
        ? await queryClient.fetchQuery({ queryKey: ['me', 'addresses'], queryFn: async () => (await http('/api/v1/me/addresses', {}, z.object({ items: z.array(BookAddress) }))).items, staleTime: 60_000 }).catch(() => null)
        : null;
      if (!cancelled) setLocation(resolveLocation({ saved: null, addresses, visitor, ipCity }));
    })();
    return () => { cancelled = true; };
  }, [ipCity, visitor, signedIn, queryClient, version]);

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
    writeCookie(clean);
    setLocation({ status: 'saved', source: 'saved', ...clean });
  }, []);
  const forget = useCallback(() => {
    try { window.localStorage.removeItem(SAVED_KEY); } catch { /* ignore */ }
    writeCookie(null);
    setLocation({ status: 'locating' });
    setVersion(v => v + 1);
  }, []);
  const reverse = useCallback(async (lat: number, lng: number) => {
    try { return await http(`/api/v1/geo/reverse?lat=${lat.toFixed(5)}&lng=${lng.toFixed(5)}`, {}, Reverse); }
    catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
  }, []);
  const chooser = useMemo(() => ({ open, show: () => setOpen(true), hide: () => setOpen(false) }), [open]);
  const value = useMemo(() => ({ location, save, forget, chooser, reverse, visitor }), [location, save, forget, chooser, reverse, visitor]);
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
