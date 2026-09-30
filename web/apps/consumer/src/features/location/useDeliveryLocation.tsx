import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { z } from 'zod';
import { http } from '@northline/client';
import type { LocationStatus } from '@northline/ui';
import { DEFAULT_MARKET, nearestMarket } from './markets';

/**
 * The delivery location every screen reads (docs/CONSUMER_WEB_PLAN.md § Location). In order:
 *   1. saved — chosen on the Location screen (S-47: `save()`), kept in this browser;
 *   2. detected — the browser's geolocation (asked once per visit), named by `GET /api/v1/geo/reverse` (S-47:
 *      "Beltline, Calgary" inside a live or pilot market; a place outside them counts as outside every market), else
 *      by the nearest live market when the api can't answer; before it answers, the CDN's IP city from the session;
 *   3. fallback — Calgary, when nothing else is known (geolocation refused, unavailable or outside every market).
 * `denied` is never reached on its own: a refusal falls back to Calgary, as the design does.
 */
export interface DeliveryLocation {
  status: LocationStatus;
  /** What the pill shows ("Calgary", "Beltline, Calgary", "1204 17 Ave SW, Calgary"); absent while locating. */
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
 * What the Location screen saves (S-47): the pill's label ("1204 17 Ave SW, Calgary"), the market's city, the
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
  try { r = await http(`/api/v1/geo/reverse?lat=${lat.toFixed(5)}&lng=${lng.toFixed(5)}`, {}, Reverse); } catch {
    const market = nearestMarket({ lat, lng });
    return market ? { label: market.city, city: market.city, province: market.province } : null;
  }
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

  useEffect(() => {
    const { local, session } = storages();
    const saved = readJson(local, SAVED_KEY, SavedLocation);
    if (saved) { setLocation({ status: 'saved', source: 'saved', ...saved }); return; }
    const known = readJson(session, DETECTED_KEY, Detected);
    if (known) { setLocation({ status: 'detected', ...known }); return; }
    if (ipCity) setLocation({ status: 'detected', label: ipCity, city: ipCity, source: 'ip' });
    const geo = geolocation === undefined ? (typeof navigator !== 'undefined' ? navigator.geolocation : null) : geolocation;
    let cancelled = false;
    const fallback = () => { if (!cancelled && !ipCity) setLocation({ status: 'fallback', label: DEFAULT_MARKET.city, city: DEFAULT_MARKET.city, lat: DEFAULT_MARKET.lat, lng: DEFAULT_MARKET.lng, province: DEFAULT_MARKET.province, source: 'default' }); };
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
  }, [ipCity, geolocation]);

  const save = useCallback((l: SavedLocation) => {
    try { storages().local?.setItem(SAVED_KEY, JSON.stringify(SavedLocation.parse(l))); } catch { /* private mode: this visit only */ }
    setLocation({ status: 'saved', source: 'saved', ...l });
  }, []);
  const forget = useCallback(() => {
    try { storages().local?.removeItem(SAVED_KEY); storages().session?.removeItem(DETECTED_KEY); } catch { /* ignore */ }
    setLocation({ status: 'fallback', label: DEFAULT_MARKET.city, city: DEFAULT_MARKET.city, province: DEFAULT_MARKET.province, source: 'default' });
  }, []);
  const value = useMemo(() => ({ location, save, forget }), [location, save, forget]);
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>;
}

/** `{ location, save, forget }` — `save` is what the Location screen calls with the chosen address. */
export function useDeliveryLocation(): LocationContextValue {
  const v = useContext(LocationContext);
  if (!v) throw new Error('useDeliveryLocation outside <DeliveryLocationProvider>');
  return v;
}
