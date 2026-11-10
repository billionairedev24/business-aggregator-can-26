import * as Location from 'expo-location';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import type { Address } from '../api/account';
import { geoApi } from '../api/geo';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../services';

/**
 * Where to deliver (S-98; the location model of the web's "UI foundation", DECISIONS 2026-10-19). In order:
 *   1. saved — the place chosen in the location sheet (key-value store `nl.location`);
 *   2. address — signed in: the account's default address (`GET /me/addresses`, `isDefault`, else the first);
 *   3. nothing — status `fallback` with no place (S-190 "Location is never required": the api's fallback market is
 *      no longer guessed). Every screen browses every live market; the place bar offers "Choose your location".
 * The phone's position is never read on launch: only the sheet's "Use my current location" asks, on the press
 * (`detected` is what that press finds, until it is saved). There is no CDN city in the app.
 */
export type LocationStatus = 'locating' | 'saved' | 'address' | 'detected' | 'fallback';

/**
 * A place: an address (label, the market's city, parts), S-192 a postal or ZIP code of any country (the code, its
 * locality and point, the market's city only inside one), or a country alone. At least one of label, city, postal
 * code or country.
 */
export interface SavedLocation {
  label?: string;
  city?: string;
  /** S-192: the place's own locality when it is not a market's ("Beverly Hills" for 90210). */
  locality?: string;
  /** S-192: ISO 3166-1 alpha-2. */
  country?: string;
  lat?: number;
  lng?: number;
  placeId?: string;
  street?: string;
  unit?: string;
  province?: string;
  postalCode?: string;
  marketId?: string;
  zoneId?: string;
  zone?: string;
}

export type DeliveryLocation = { status: LocationStatus } & Partial<SavedLocation>;

export const SAVED_LOCATION_KEY = 'nl.location';

interface LocationValue {
  location: DeliveryLocation;
  save(l: SavedLocation): Promise<void>;
  forget(): Promise<void>;
}

const LocationContext = createContext<LocationValue | null>(null);

function parseSaved(raw: string | null): SavedLocation | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as SavedLocation;
    const text = (x: unknown) => typeof x === 'string' && x.length > 0;
    return v && typeof v === 'object' && (text(v.label) || text(v.city) || text(v.postalCode) || text(v.country)) ? v : null;
  } catch {
    return null;
  }
}

/** What pressing "Use my current location" ends in (the only place the app asks for the phone's position). */
export type HereResult =
  | { ok: true; place: DeliveryLocation }
  | { ok: false; reason: 'denied' | 'settings' | 'unavailable' | 'outside'; place?: SavedLocation };

/**
 * The place bar's line (S-192, Amazon's "{city} {postal code}"): "{locality or city} {postal code}", else the city or the
 * label, else the country's name; undefined when nothing is known.
 */
export function placeLine(l: DeliveryLocation, countryName: (code: string) => string): string | undefined {
  if (l.status === 'locating' || l.status === 'fallback') return undefined;
  const town = l.locality ?? l.city;
  if (l.postalCode) return town ? `${town} ${l.postalCode}` : l.postalCode;
  if (town) return town;
  if (l.label) return l.label;
  return l.country ? countryName(l.country) : undefined;
}

/** Asks for the location permission (now, on the press), reads the position and names it inside a live / pilot market. */
export async function locateHere(): Promise<HereResult> {
  try {
    const asked = await Location.requestForegroundPermissionsAsync();
    if (!asked.granted) return { ok: false, reason: asked.canAskAgain ? 'denied' : 'settings' };
    const fix = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    const { latitude: lat, longitude: lng } = fix.coords;
    const named = await nameOf(lat, lng);
    if (named) return { ok: true, place: named };
    // S-192: outside every market the point is still a place — the screens rank the nearest markets first
    const r = await geoApi(services().api).reverse(lat, lng).catch(() => null);
    return { ok: false, reason: 'outside', ...(r?.city ? { place: { label: r.city, locality: r.city, lat, lng } } : {}) };
  } catch {
    return { ok: false, reason: 'unavailable' };
  }
}

/** The account's default address (else its first) as a place, or null (none, or no answer). */
export async function defaultAddress(): Promise<DeliveryLocation | null> {
  const items = await services()
    .api.get<{ items: Address[] }>('/me/addresses')
    .then((r) => r?.items ?? [])
    .catch(() => [] as Address[]);
  const a = items.find((x) => x.isDefault) ?? items[0];
  return a ? { status: 'address', ...placeOfAddress(a) } : null;
}

/** An account address as the place the screens read. */
export function placeOfAddress(a: Address): SavedLocation {
  return {
    label: `${a.street}, ${a.city}`,
    city: a.city,
    street: a.street,
    ...(a.unit ? { unit: a.unit } : {}),
    province: a.province,
    postalCode: a.postal,
  };
}

/** `GET /geo/reverse`: a place inside a live or pilot market, else null (outside every market, or no answer). */
export async function nameOf(lat: number, lng: number): Promise<DeliveryLocation | null> {
  const r = await geoApi(services().api).reverse(lat, lng).catch(() => null);
  if (!r || !r.market || (r.market.stage !== 'live' && r.market.stage !== 'pilot')) return null;
  return {
    status: 'detected',
    label: r.label,
    city: r.city,
    lat,
    lng,
    ...(r.province ? { province: r.province } : {}),
    marketId: r.market.id,
    ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
  };
}


async function resolve(signedIn: boolean): Promise<DeliveryLocation> {
  const saved = parseSaved(await services().store.getItem(SAVED_LOCATION_KEY).catch(() => null));
  if (saved) return { status: 'saved', ...saved };
  return (signedIn ? await defaultAddress() : null) ?? { status: 'fallback' };
}

export function DeliveryLocationProvider({ children }: { children: ReactNode }) {
  const { status: auth } = useAuth();
  const [location, setLocation] = useState<DeliveryLocation>({ status: 'locating' });

  // again when the person signs in or out (their default address comes or goes); a saved choice always wins
  useEffect(() => {
    if (auth === 'loading') return;
    let alive = true;
    void resolve(auth === 'signedIn').then((next) => {
      if (alive) setLocation(next);
    });
    return () => {
      alive = false;
    };
  }, [auth]);

  const save = useCallback(async (l: SavedLocation) => {
    setLocation({ status: 'saved', ...l });
    await services().store.setItem(SAVED_LOCATION_KEY, JSON.stringify(l)).catch(() => undefined);
  }, []);

  const forget = useCallback(async () => {
    await services().store.removeItem(SAVED_LOCATION_KEY).catch(() => undefined);
    setLocation(await resolve(auth === 'signedIn'));
  }, [auth]);

  const value = useMemo(() => ({ location, save, forget }), [location, save, forget]);
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>;
}

export function useDeliveryLocation(): LocationValue {
  const ctx = useContext(LocationContext);
  if (!ctx) throw new Error('useDeliveryLocation outside DeliveryLocationProvider');
  return ctx;
}
