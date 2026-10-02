import * as Location from 'expo-location';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { geoApi } from '../api/geo';
import { services } from '../services';

/**
 * Where to deliver (S-98; MOBILE_PLAN § Contracts › Delivery location). In order:
 *   1. saved — the address chosen on the Location screen (key-value store `nl.location`);
 *   2. detected — the phone's position, **only if the person already allowed location** (the app never asks outside
 *      the Location screen's "Use my location"), named by `GET /geo/reverse` inside a live or pilot market;
 *   3. fallback — the api's fallback market (`GET /geo/markets` → `fallback`, from region configuration); none
 *      configured or no answer → no place (screens ask to set one).
 */
export type LocationStatus = 'locating' | 'saved' | 'detected' | 'fallback';

export interface SavedLocation {
  label: string;
  city: string;
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
    return typeof v.label === 'string' && typeof v.city === 'string' && v.label && v.city ? v : null;
  } catch {
    return null;
  }
}

/** The phone's position named by the api, if location is already allowed and the place is in a live / pilot market. */
export async function detectedLocation(): Promise<DeliveryLocation | null> {
  try {
    const permission = await Location.getForegroundPermissionsAsync();
    if (!permission.granted) return null;
    const fix = (await Location.getLastKnownPositionAsync({ maxAge: 600_000 })) ?? (await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }));
    return await nameOf(fix.coords.latitude, fix.coords.longitude);
  } catch {
    return null;
  }
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

export async function fallbackLocation(): Promise<DeliveryLocation> {
  const m = await geoApi(services().api)
    .markets()
    .then((r) => r?.fallback ?? null)
    .catch(() => null);
  return m
    ? { status: 'fallback', label: m.city, city: m.city, province: m.province, marketId: m.id, ...(m.lat != null && m.lng != null ? { lat: m.lat, lng: m.lng } : {}) }
    : { status: 'fallback' };
}

export function DeliveryLocationProvider({ children }: { children: ReactNode }) {
  const [location, setLocation] = useState<DeliveryLocation>({ status: 'locating' });

  useEffect(() => {
    let alive = true;
    void (async () => {
      const saved = parseSaved(await services().store.getItem(SAVED_LOCATION_KEY).catch(() => null));
      if (saved) {
        if (alive) setLocation({ status: 'saved', ...saved });
        return;
      }
      const next = (await detectedLocation()) ?? (await fallbackLocation());
      if (alive) setLocation(next);
    })();
    return () => {
      alive = false;
    };
  }, []);

  const save = useCallback(async (l: SavedLocation) => {
    setLocation({ status: 'saved', ...l });
    await services().store.setItem(SAVED_LOCATION_KEY, JSON.stringify(l)).catch(() => undefined);
  }, []);

  const forget = useCallback(async () => {
    await services().store.removeItem(SAVED_LOCATION_KEY).catch(() => undefined);
    setLocation(await fallbackLocation());
  }, []);

  const value = useMemo(() => ({ location, save, forget }), [location, save, forget]);
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>;
}

export function useDeliveryLocation(): LocationValue {
  const ctx = useContext(LocationContext);
  if (!ctx) throw new Error('useDeliveryLocation outside DeliveryLocationProvider');
  return ctx;
}
