import { z } from 'zod';
import type { LocationStatus } from '@northline/ui';

/**
 * Where the visitor is, and how we know — the pure part of the consumer location (2026-10-19 rebuild), unit-tested in
 * sources.test.ts. The page never asks the browser for its position on its own: the sources, in order, are
 *   1. saved   — what the person chose in the location dialog (this browser, localStorage);
 *   2. address — the signed-in person's default address (`GET /api/v1/me/addresses`);
 *   3. ip      — the CDN's city for this visit (the session's `location.city`);
 *   4. default — the api's fallback market (`GET /api/v1/geo/markets` → `fallback`: region configuration, never a city
 *                in code); none configured → nothing known, and the control asks to set a location.
 * The device's position is read only when the person presses "Use my current location" ({@link locateDevice}).
 */
export interface DeliveryLocation {
  status: LocationStatus;
  /** What the control shows ("{city}", "{neighbourhood}, {city}", "{street}, {city}"); absent while locating. */
  label?: string;
  /** The market's city (search and listings filter by it). */
  city?: string;
  lat?: number;
  lng?: number;
  source?: 'saved' | 'device' | 'address' | 'ip' | 'default';
  /** When known: two-letter province (tax), the chosen address's parts, its market and delivery zone. */
  province?: string;
  street?: string;
  unit?: string;
  postalCode?: string;
  placeId?: string;
  marketId?: string;
  zoneId?: string;
  zone?: string;
}

/** What the dialog saves: the label, the market's city, and what is known of the place (address parts, market, zone). */
export const SavedLocation = z.object({
  label: z.string().min(1), city: z.string().min(1), lat: z.number().optional(), lng: z.number().optional(), placeId: z.string().optional(),
  street: z.string().optional(), unit: z.string().optional(), province: z.string().optional(), postalCode: z.string().optional(),
  marketId: z.string().optional(), zoneId: z.string().optional(), zone: z.string().optional(),
});
export type SavedLocation = z.infer<typeof SavedLocation>;
export const SAVED_KEY = 'nl.location';

/** A saved address of the signed-in person (`GET /api/v1/me/addresses`, the fields this needs). */
export const BookAddress = z.object({
  id: z.string(), label: z.string().nullish(), street: z.string(), unit: z.string().nullish(), city: z.string(),
  province: z.string().nullish(), postal: z.string().nullish(), isDefault: z.boolean().nullish(),
});
export type BookAddress = z.infer<typeof BookAddress>;

/** A market as `GET /api/v1/geo/markets` describes it. */
export interface MarketRef { id: string; city: string; province: string; stage?: string; lat?: number | null; lng?: number | null }

/** A saved address as a location to save ("{street}, {city}"). */
export function fromAddress(a: BookAddress): SavedLocation {
  return {
    label: `${a.street}, ${a.city}`, city: a.city, street: a.street,
    ...(a.unit ? { unit: a.unit } : {}), ...(a.province ? { province: a.province } : {}), ...(a.postal ? { postalCode: a.postal } : {}),
  };
}

/** A market as a location to save ("{city}"). */
export function fromMarket(m: MarketRef): SavedLocation {
  return {
    label: m.city, city: m.city, province: m.province, marketId: m.id,
    ...(m.lat != null && m.lng != null ? { lat: m.lat, lng: m.lng } : {}),
  };
}

export interface Known {
  saved: SavedLocation | null;
  /** The signed-in person's addresses (null: a guest, or not loaded). */
  addresses: readonly BookAddress[] | null;
  ipCity: string | null | undefined;
  fallback: MarketRef | null;
}

/** The location the control shows, from what is known — saved → default address → IP city → fallback market. */
export function resolveLocation({ saved, addresses, ipCity, fallback }: Known): DeliveryLocation {
  if (saved) return { status: 'saved', source: 'saved', ...saved };
  const home = addresses?.find(a => a.isDefault) ?? addresses?.[0];
  if (home) return { status: 'address', source: 'address', ...fromAddress(home) };
  if (ipCity) return { status: 'detected', source: 'ip', label: ipCity, city: ipCity };
  if (fallback) return { status: 'fallback', source: 'default', ...fromMarket(fallback) };
  return { status: 'denied' };
}

/** `GET /api/v1/geo/reverse` — `market` is null outside every market; 404 when nothing is there. */
export const Reverse = z.object({
  label: z.string(), city: z.string(), province: z.string().nullish(),
  market: z.object({ id: z.string(), stage: z.string() }).nullish(),
  zone: z.object({ id: z.string(), name: z.string() }).nullish(),
});
export type Reverse = z.infer<typeof Reverse>;

/** What pressing "Use my current location" ends in. */
export type Located =
  | { kind: 'found'; location: SavedLocation }
  | { kind: 'denied' }       // the browser or the person refused
  | { kind: 'unavailable' }  // no geolocation, no fix, or timed out
  | { kind: 'outside'; place?: string } // a place outside every live or pilot market
  | { kind: 'error' };       // the api couldn't name the place

/**
 * Asks the browser for the device's position (this is the only place that does, and only on a press), then names it
 * with `reverse` (`GET /api/v1/geo/reverse`). Never throws.
 */
export function locateDevice(geo: Geolocation | null | undefined, reverse: (lat: number, lng: number) => Promise<Reverse | null>): Promise<Located> {
  if (!geo) return Promise.resolve({ kind: 'unavailable' });
  return new Promise(resolve => {
    geo.getCurrentPosition(async ({ coords }) => {
      let r: Reverse | null;
      try { r = await reverse(coords.latitude, coords.longitude); } catch { resolve({ kind: 'error' }); return; }
      if (!r) { resolve({ kind: 'outside' }); return; }
      if (r.market === null || (r.market && r.market.stage !== 'live' && r.market.stage !== 'pilot')) { resolve({ kind: 'outside', place: r.city }); return; }
      resolve({ kind: 'found', location: {
        label: r.label, city: r.city, lat: coords.latitude, lng: coords.longitude,
        ...(r.province ? { province: r.province } : {}), ...(r.market ? { marketId: r.market.id } : {}),
        ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
      } });
    }, err => resolve(err?.code === 1 ? { kind: 'denied' } : { kind: 'unavailable' }), { maximumAge: 600_000, timeout: 15_000, enableHighAccuracy: false });
  });
}
