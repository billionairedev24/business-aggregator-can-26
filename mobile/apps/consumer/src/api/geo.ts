import type { ApiClient } from '@northline/mobile-kit';

/**
 * The region model and the geo api (S-47, S-134; public under `/api/v1/geo`, the Google key stays on the server):
 * provinces and markets with their stage, the fallback market, address suggestions (Canada only, one session token per
 * search), an address resolved to a market and zone, the device's position named, the waitlist. Guests may call all
 * of it (`auth: 'optional'`).
 */
export type Stage = 'off' | 'waitlist' | 'pilot' | 'live';
export interface Market { id: string; city: string; province: string; stage: Stage; lat?: number | null; lng?: number | null }
export interface Zone { id: string; name: string; runsPerDay?: number | null; feeStdCents?: number | null; feePlusCents?: number | null; minBasketCents?: number | null }
export interface Province { code: string; name: string; stage: Stage; taxBps: number; markets: Market[] }
export interface Markets { items: Province[]; fallback?: Market | null }
export interface Suggestions { items: Array<{ placeId: string; main: string; secondary: string }>; attribution: string }
export interface Resolution { market?: Market | null; zone?: Zone | null; waitlist?: { regionId: string; name: string; stage: Stage } | null }
export interface Address {
  placeId: string;
  label: string;
  street: string;
  city?: string | null;
  province?: string | null;
  postalCode?: string | null;
  neighbourhood?: string | null;
  lat: number;
  lng: number;
  resolution: Resolution;
}
export interface Reverse {
  label: string;
  city: string;
  province?: string | null;
  market?: { id: string; stage: Stage } | null;
  zone?: { id: string; name: string } | null;
}
/** `GET /geo/regions` (S-134): what the app needs of it. */
export interface Regions {
  platformTimeZone: string;
  defaultProvince?: string | null;
  /** S-116: `frenchFirst` — the place's language rule (region configuration); absent from older servers. */
  provinces: Array<{ code: string; name: string; status: Stage; timeZone: string; frenchFirst?: boolean }>;
  markets: Array<{ id: string; city: string; province: string; timeZone: string; status: Stage; frenchFirst?: boolean }>;
}

/**
 * S-116 (Loi 96 readiness): whether a place is French-first — its market's rule (by id, else city), else its
 * province's (region configuration; the app never names a place). Unknown place: no.
 */
export function isFrenchFirst(regions: Regions | undefined, place: { province?: string | null; marketId?: string | null; city?: string | null }): boolean {
  if (!regions) return false;
  const market = regions.markets.find((m) => (place.marketId && m.id === place.marketId) || (place.city && m.city.toLowerCase() === place.city.toLowerCase()));
  if (market?.frenchFirst !== undefined) return market.frenchFirst;
  const province = place.province ?? market?.province;
  return regions.provinces.find((p) => p.code === province)?.frenchFirst ?? false;
}

const q = (p: Record<string, string | number | undefined>) =>
  Object.entries(p)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&');

export const geoApi = (api: ApiClient) => ({
  regions: (lang: string) => api.get<Regions>(`/geo/regions?${q({ lang })}`, { auth: 'optional' }),
  markets: () => api.get<Markets>('/geo/markets', { auth: 'optional' }),
  suggest: (text: string, session: string, near?: { lat?: number; lng?: number }) =>
    api.get<Suggestions>(`/geo/autocomplete?${q({ q: text.trim(), session, lat: near?.lat?.toFixed(5), lng: near?.lng?.toFixed(5) })}`, { auth: 'optional' }),
  place: (placeId: string, session: string) => api.get<Address>(`/geo/places/${encodeURIComponent(placeId)}?${q({ session })}`, { auth: 'optional' }),
  reverse: (lat: number, lng: number) => api.get<Reverse>(`/geo/reverse?${q({ lat: lat.toFixed(5), lng: lng.toFixed(5) })}`, { auth: 'optional' }),
  joinWaitlist: (regionId: string, email?: string) => api.post<{ joined: boolean }>('/geo/waitlist', { auth: 'optional', json: { regionId, ...(email ? { email } : {}) } }),
});
