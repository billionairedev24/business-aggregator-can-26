import { queryOptions, useMutation, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * The Location screen's api (S-47, all public under `/api/v1/geo`; the key to Google stays on the server):
 * provinces and markets, address suggestions (Google Places, restricted to Canada, one session token per search),
 * the chosen address resolved to a market and zone, and the waitlist.
 */
export const Stage = z.enum(['off', 'waitlist', 'pilot', 'live']);
export type Stage = z.infer<typeof Stage>;

export const Market = z.object({ id: z.string(), city: z.string(), province: z.string(), stage: Stage });
export type Market = z.infer<typeof Market>;

export const Zone = z.object({
  id: z.string(), name: z.string(),
  runsPerDay: z.number().int().nullish(), feeStdCents: z.number().int().nullish(), feePlusCents: z.number().int().nullish(), minBasketCents: z.number().int().nullish(),
});
export type Zone = z.infer<typeof Zone>;

export const Resolution = z.object({
  market: Market.nullish(),
  zone: Zone.nullish(),
  waitlist: z.object({ regionId: z.string(), name: z.string(), stage: Stage }).nullish(),
});
export type Resolution = z.infer<typeof Resolution>;

export const Province = z.object({ code: z.string(), name: z.string(), stage: Stage, markets: z.array(Market) });
export type Province = z.infer<typeof Province>;

export const Suggestions = z.object({
  items: z.array(z.object({ placeId: z.string(), main: z.string(), secondary: z.string() })),
  attribution: z.string(),
});
export type Suggestions = z.infer<typeof Suggestions>;

export const Address = z.object({
  placeId: z.string(), label: z.string(), street: z.string(),
  city: z.string().nullish(), province: z.string().nullish(), postalCode: z.string().nullish(), neighbourhood: z.string().nullish(),
  lat: z.number(), lng: z.number(),
  resolution: Resolution,
});
export type Address = z.infer<typeof Address>;

export const marketsQuery = queryOptions({
  queryKey: ['public', 'geo', 'markets'],
  queryFn: () => http('/api/v1/geo/markets', {}, z.object({ items: z.array(Province) })).then(r => r.items),
  staleTime: 5 * 60_000,
});

export const useMarkets = () => useQuery(marketsQuery);

const params = (p: Record<string, string | number | undefined>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&');

/** Suggestions for `input` (≥ 3 characters; the caller debounces), biased to `near`. */
export function useSuggestions(input: string, session: string, near?: { lat?: number; lng?: number }) {
  const q = input.trim();
  return useQuery({
    queryKey: ['geo', 'autocomplete', q.toLowerCase(), near?.lat?.toFixed(2), near?.lng?.toFixed(2)],
    queryFn: () => http(`/api/v1/geo/autocomplete?${params({ q, session, lat: near?.lat?.toFixed(5), lng: near?.lng?.toFixed(5) })}`, {}, Suggestions),
    enabled: q.length >= 3,
    staleTime: 60_000,
    retry: false,
  });
}

/** The chosen suggestion's address (ends the Google session). */
export const addressQuery = (placeId: string, session: string) => queryOptions({
  queryKey: ['geo', 'place', placeId],
  queryFn: () => http(`/api/v1/geo/places/${encodeURIComponent(placeId)}?${params({ session })}`, {}, Address),
  staleTime: Infinity,
  retry: false,
});

export const useAddress = (placeId: string | null, session: string) =>
  useQuery({ ...addressQuery(placeId ?? '', session), enabled: !!placeId });

export function useJoinWaitlist() {
  return useMutation({
    mutationFn: (body: { regionId: string; email?: string }) =>
      http('/api/v1/geo/waitlist', { method: 'POST', body }, z.object({ joined: z.boolean() })),
  });
}

/** A random session token for one address search (Google bills a search + its details call as one session). */
export function newSessionToken(): string {
  const c = globalThis.crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
