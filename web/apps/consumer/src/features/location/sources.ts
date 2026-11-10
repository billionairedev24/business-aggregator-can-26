import { z } from 'zod';
import type { LocationStatus } from '@northline/ui';

/**
 * Where the visitor is, and how we know — the pure part of the consumer location (2026-10-19 rebuild; S-190 "Location
 * is never required"), unit-tested in sources.test.ts. The page never asks the browser for its position on its own,
 * and never blocks on it: the sources, in order, are
 *   1. saved   — what the person chose in the location dialog (this browser, localStorage);
 *   2. address — the signed-in person's default address (`GET /api/v1/me/addresses`);
 *   3. ip      — S-192: the visitor's place detected from the request (`GET /api/v1/geo/visitor`: the edge's visitor
 *                location headers or a GeoIP database, read during the server render), else the session's
 *                `location.city`; a city, or only a country;
 *   4. nothing — status `denied`: the header offers "Choose location" and every page shows all markets, popular
 *                first. S-190 dropped the api's fallback market as a source: guessing a city the person isn't in hid
 *                everything else.
 * The device's position is read only when the person presses "Use my current location" ({@link locateDevice}).
 * S-192: a place may be a postal or ZIP code of any country, or a country alone ("deliver to another country") — no
 * country is assumed in code (S-192a brings per-country formats, currencies and tax).
 */
export interface DeliveryLocation {
  status: LocationStatus;
  /** What the control shows ("{city}", "{neighbourhood}, {city}", "{street}, {city}"); absent while locating. */
  label?: string;
  /** The market's city (search and listings filter by it). Absent for a place outside every market (S-192). */
  city?: string;
  /** S-192: the place's own locality when it is not a market ("Beverly Hills" for 90210), shown in the header. */
  locality?: string;
  /** S-192: ISO 3166-1 alpha-2, when known. */
  country?: string;
  lat?: number;
  lng?: number;
  source?: 'saved' | 'device' | 'address' | 'ip';
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

/**
 * What the dialog saves: what is known of the place — an address (label, market city, parts, market, zone), a postal
 * code (S-192: the code, its locality and centre, the market when one covers it), or a country alone. At least one of
 * label, city, postal code or country.
 */
export const SavedLocation = z.object({
  label: z.string().min(1).optional(), city: z.string().min(1).optional(), lat: z.number().optional(), lng: z.number().optional(), placeId: z.string().optional(),
  street: z.string().optional(), unit: z.string().optional(), province: z.string().optional(), postalCode: z.string().optional(),
  marketId: z.string().optional(), zoneId: z.string().optional(), zone: z.string().optional(),
  locality: z.string().optional(), country: z.string().regex(/^[A-Z]{2}$/).optional(),
}).refine(l => !!(l.label || l.city || l.postalCode || l.country), { message: 'empty place' });
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

/** S-192: the visitor's place detected from the request (`GET /api/v1/geo/visitor`); every part nullable. */
export const Visitor = z.object({
  country: z.string().nullish(), region: z.string().nullish(), city: z.string().nullish(), postalCode: z.string().nullish(),
  lat: z.number().nullish(), lng: z.number().nullish(), source: z.string().nullish(),
});
export type Visitor = z.infer<typeof Visitor>;

export interface Known {
  saved: SavedLocation | null;
  /** The signed-in person's addresses (null: a guest, or not loaded). */
  addresses: readonly BookAddress[] | null;
  /** S-192: the detected place (a city, or a country alone), else the session's CDN city. */
  visitor?: Visitor | null;
  ipCity?: string | null;
}

/**
 * The detected place as a location (S-192): its city (the reads filter by it, as the CDN city did — S-190), postal code,
 * country and point; a country alone is a place too (the header names it), but `knowsPlace` stays false. The region is
 * not kept as a province: a guess from an address never switches the language or the privacy law.
 */
export function fromVisitor(v: Visitor | null | undefined, ipCity?: string | null): DeliveryLocation | null {
  const city = v?.city ?? ipCity ?? undefined;
  const country = v?.country ?? undefined;
  if (!city && !country) return null;
  return {
    status: 'detected', source: 'ip', ...(city ? { label: city, city } : {}), ...(country ? { country } : {}),
    ...(v?.postalCode && city ? { postalCode: v.postalCode } : {}),
    ...(v?.lat != null && v?.lng != null && city ? { lat: v.lat, lng: v.lng } : {}),
  };
}

/** The location the control shows, from what is known — saved → default address → detected → nothing. */
export function resolveLocation({ saved, addresses, visitor, ipCity }: Known): DeliveryLocation {
  if (saved) return { status: 'saved', source: 'saved', ...saved };
  const home = addresses?.find(a => a.isDefault) ?? addresses?.[0];
  if (home) return { status: 'address', source: 'address', ...fromAddress(home) };
  return fromVisitor(visitor, ipCity) ?? { status: 'denied' };
}

/**
 * The header's place line (S-192, Amazon's "Calgary T2P 1B5"): "{locality or city} {postal code}", else the city, else
 * the country's name; undefined when nothing is known ("Choose location") or while locating.
 */
export function headerPlace(l: DeliveryLocation, countryName: (code: string) => string): string | undefined {
  if (l.status === 'locating' || l.status === 'denied') return undefined;
  const town = l.locality ?? l.city;
  if (l.postalCode) return town ? `${town} ${l.postalCode}` : l.postalCode;
  if (town) return town;
  if (l.label) return l.label;
  return l.country ? countryName(l.country) : undefined;
}

/**
 * S-192: the saved choice, mirrored into the `nl.loc` cookie so the server's render already says where to deliver (no
 * "Finding your area…" and no detected city before the browser has read its storage). Only what the header and the
 * reads need: never a street. Read back with {@link fromCookie}.
 */
export const PLACE_COOKIE = 'nl.loc';
export function toCookie(l: SavedLocation): string {
  const keep = { city: l.city, locality: l.locality, postalCode: l.postalCode, country: l.country, marketId: l.marketId, lat: l.lat, lng: l.lng, province: l.province, zone: l.zone };
  return JSON.stringify(Object.fromEntries(Object.entries(keep).filter(([, v]) => v !== undefined)));
}
export function fromCookie(raw: string | null | undefined): SavedLocation | null {
  if (!raw) return null;
  try {
    const parsed = SavedLocation.safeParse(JSON.parse(raw));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

/** A place is known (the pages rank and filter by it); `denied` = nothing known, `locating` = not read yet. */
export const knowsPlace = (l: DeliveryLocation): boolean => l.status !== 'locating' && l.status !== 'denied' && !!l.city;

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
  // S-192: outside every live or pilot market — still a place to save (its point ranks the nearest markets first)
  | { kind: 'outside'; place?: string; location?: SavedLocation }
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
      const point = { lat: coords.latitude, lng: coords.longitude };
      if (!r) { resolve({ kind: 'outside' }); return; }
      if (r.market === null || (r.market && r.market.stage !== 'live' && r.market.stage !== 'pilot')) {
        resolve({ kind: 'outside', place: r.city, location: { label: r.city, locality: r.city, ...point, ...(r.province ? { province: r.province } : {}) } });
        return;
      }
      resolve({ kind: 'found', location: {
        label: r.label, city: r.city, lat: coords.latitude, lng: coords.longitude,
        ...(r.province ? { province: r.province } : {}), ...(r.market ? { marketId: r.market.id } : {}),
        ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
      } });
    }, err => resolve(err?.code === 1 ? { kind: 'denied' } : { kind: 'unavailable' }), { maximumAge: 600_000, timeout: 15_000, enableHighAccuracy: false });
  });
}
