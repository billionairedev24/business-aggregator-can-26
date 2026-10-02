import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { z } from 'zod';
import { http } from '@northline/client';
import type { LocationStatus } from '@northline/ui';
import { useQueryClient } from '@tanstack/react-query';
import { marketsQuery } from './api';

/**
 * The delivery location every screen reads (docs/CONSUMER_WEB_PLAN.md § Location). In order:
 *   1. saved — chosen on the Location screen (S-47: `save()`), kept in this browser;
 *   2. detected — the browser's geolocation (asked once per visit), named by `GET /api/v1/geo/reverse` (S-47:
 *      "{neighbourhood}, {city}" inside a live or pilot market; a place outside them, or no answer, counts as outside
 *      every market); before it answers, the CDN's IP city from the session;
 *   3. fallback — the api's fallback market (`GET /api/v1/geo/markets` → `fallback`: the first live market of the
 *      default market's province; region configuration, never a city in code) when nothing else is known
 *      (geolocation refused, unavailable or outside every market); none configured → the pill asks to set a location.
 * `denied` is never reached on its own: a refusal falls back, as the design does.
 */
export interface DeliveryLocation {
  status: LocationStatus;
  /** What the pill shows ("{city}", "{neighbourhood}, {city}", "{street}, {city}"); absent while locating. */
  label?: string;
  /** The market's city (search and listings filter by it). */
  city?: string;
  lat?: number;
  lng?: number;
  source?: 'saved' | 'device' | 'ip' | 'default';
  /** S-47, when known: two-letter province (tax), the chosen address's parts, its market and delivery zone. */
  province?: string;
  street?: string;
  unit?: string;
  postalCode?: string;
  placeId?: string;
  marketId?: string;
  zoneId?: string;
  zone?: string;
}

/**
 * What the Location screen saves (S-47): the pill's label ("{street}, {city}"), the market's city, the
 * coordinates, and — additive since S-47 — the address parts checkout needs (street, unit / buzzer / drop-off note,
 * province, postal code) and the market / zone it resolved to.
 */
export const SavedLocation = z.object({
  label: z.string().min(1), city: z.string().min(1), lat: z.number().optional(), lng: z.number().optional(), placeId: z.string().optional(),
  street: z.string().optional(), unit: z.string().optional(), province: z.string().optional(), postalCode: z.string().optional(),
  marketId: z.string().optional(), zoneId: z.string().optional(), zone: z.string().optional(),
});
export type SavedLocation = z.infer<typeof SavedLocation>;
export const SAVED_KEY = 'nl.location';
const DETECTED_KEY = 'nl.location.detected';

/** `GET /api/v1/geo/reverse` — `market` (S-47) is null outside every market; absent from older answers. */
const Reverse = z.object({
  label: z.string(), city: z.string(), province: z.string().nullish(),
  market: z.object({ id: z.string(), stage: z.string() }).nullish(),
  zone: z.object({ id: z.string(), name: z.string() }).nullish(),
});

function readJson<T>(storage: Storage | undefined, key: string, schema: z.ZodType<T>): T | null {
  try { const raw = storage?.getItem(key); return raw ? schema.parse(JSON.parse(raw)) : null; } catch { return null; }
}
const storages = () => (typeof window === 'undefined' ? { local: undefined, session: undefined } : { local: window.localStorage, session: window.sessionStorage });

const Detected = z.object({
  label: z.string(), city: z.string(), lat: z.number().optional(), lng: z.number().optional(), source: z.enum(['device', 'ip']),
  province: z.string().optional(), marketId: z.string().optional(), zoneId: z.string().optional(), zone: z.string().optional(),
});
type Named = { label: string; city: string; province?: string; marketId?: string; zoneId?: string; zone?: string };

/** Where the device is, for the pill: the api's name inside a live / pilot market; null = outside every market. */
async function nameOf(lat: number, lng: number): Promise<Named | null> {
  let r: z.infer<typeof Reverse>;
  try { r = await http(`/api/v1/geo/reverse?lat=${lat.toFixed(5)}&lng=${lng.toFixed(5)}`, {}, Reverse); } catch { return null; }
  if (r.market === null || (r.market && r.market.stage !== 'live' && r.market.stage !== 'pilot')) return null;
  return {
    label: r.label, city: r.city,
    ...(r.province ? { province: r.province } : {}), ...(r.market ? { marketId: r.market.id } : {}),
    ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
  };
}

interface LocationContextValue { location: DeliveryLocation; save: (l: SavedLocation) => void; forget: () => void }
const LocationContext = createContext<LocationContextValue | null>(null);

export interface DeliveryLocationProviderProps {
  /** The CDN's city for this visitor (session `location.city`), when known. */
  ipCity?: string | null;
  children: ReactNode;
  /** Injected in tests. */
  geolocation?: Geolocation | null;
}

export function DeliveryLocationProvider({ ipCity, children, geolocation }: DeliveryLocationProviderProps) {
  // Server rendering and the first client render agree on "locating"; the browser-only sources are read after mount.
  const [location, setLocation] = useState<DeliveryLocation>({ status: 'locating' });
  const queryClient = useQueryClient();
  /** The api's fallback market as a location (no label when none is configured or the api can't answer). */
  const fallbackLocation = useCallback(async (): Promise<DeliveryLocation> => {
    const m = await queryClient.fetchQuery(marketsQuery).then(r => r.fallback ?? null, () => null);
    return m
      ? { status: 'fallback', label: m.city, city: m.city, province: m.province, ...(m.lat != null && m.lng != null ? { lat: m.lat, lng: m.lng } : {}), marketId: m.id, source: 'default' }
      : { status: 'fallback', source: 'default' };
  }, [queryClient]);

  useEffect(() => {
    const { local, session } = storages();
    const saved = readJson(local, SAVED_KEY, SavedLocation);
    if (saved) { setLocation({ status: 'saved', source: 'saved', ...saved }); return; }
    const known = readJson(session, DETECTED_KEY, Detected);
    if (known) { setLocation({ status: 'detected', ...known }); return; }
    if (ipCity) setLocation({ status: 'detected', label: ipCity, city: ipCity, source: 'ip' });
    const geo = geolocation === undefined ? (typeof navigator !== 'undefined' ? navigator.geolocation : null) : geolocation;
    let cancelled = false;
    const fallback = () => {
      if (cancelled || ipCity) return;
      void fallbackLocation().then(l => { if (!cancelled) setLocation(l); });
    };
    if (!geo) { fallback(); return; }
    // The design's 4 s: a prompt left unanswered doesn't keep the pill "Locating…".
    const timer = window.setTimeout(fallback, 4000);
    geo.getCurrentPosition(async ({ coords }) => {
      window.clearTimeout(timer);
      const named = await nameOf(coords.latitude, coords.longitude);
      if (cancelled) return;
      if (!named) { fallback(); return; }
      const detected = { ...named, lat: coords.latitude, lng: coords.longitude, source: 'device' as const };
      try { session?.setItem(DETECTED_KEY, JSON.stringify(detected)); } catch { /* private mode */ }
      setLocation({ status: 'detected', ...detected });
    }, () => { window.clearTimeout(timer); fallback(); }, { maximumAge: 600_000, timeout: 3500 });
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [ipCity, geolocation, fallbackLocation]);

  const save = useCallback((l: SavedLocation) => {
    try { storages().local?.setItem(SAVED_KEY, JSON.stringify(SavedLocation.parse(l))); } catch { /* private mode: this visit only */ }
    setLocation({ status: 'saved', source: 'saved', ...l });
  }, []);
  const forget = useCallback(() => {
    try { storages().local?.removeItem(SAVED_KEY); storages().session?.removeItem(DETECTED_KEY); } catch { /* ignore */ }
    void fallbackLocation().then(setLocation);
  }, [fallbackLocation]);
  const value = useMemo(() => ({ location, save, forget }), [location, save, forget]);
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>;
}

/** `{ location, save, forget }` — `save` is what the Location screen calls with the chosen address. */
/**
 * S-116: the delivery location where a page may sit outside <DeliveryLocationProvider> (the sign-in and registration
 * pages): the provider's when there is one, else the location saved in this browser, else none.
 */
export function useKnownLocation(): DeliveryLocation | null {
  const v = useContext(LocationContext);
  if (v) return v.location;
  const saved = readJson(storages().local, SAVED_KEY, SavedLocation);
  return saved ? { status: 'saved', source: 'saved', ...saved } : null;
}

export function useDeliveryLocation(): LocationContextValue {
  const v = useContext(LocationContext);
  if (!v) throw new Error('useDeliveryLocation outside <DeliveryLocationProvider>');
  return v;
}
