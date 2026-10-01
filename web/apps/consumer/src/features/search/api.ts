import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';
import { forwardedFor } from '../../lib/request';

/**
 * Search (S-48) on the S-44 API — `GET /api/v1/search` and `/search/suggest`, public and anonymous
 * (docs/CONSUMER_WEB_PLAN.md § Search). The page's own parameters are its URL (`SearchParams`, shareable and the same
 * HTML for everyone); the visitor's location (province = `market`, coordinates) is added in the browser once known.
 */

export const SCOPES = ['all', 'services', 'shop', 'food'] as const;
export type Scope = (typeof SCOPES)[number];
export const SORTS = ['relevance', 'distance', 'price_asc', 'price_desc', 'rating'] as const;
export type Sort = (typeof SORTS)[number];
export const RADII = [3, 10, 25] as const;

// The router parses `?openNow=true` into a boolean and `?maxPrice=999` into a number (JSON values).
const flag = z.literal(true).optional().catch(undefined);
const list = z.string().regex(/^[a-z_]+(,[a-z_]+)*$/).max(120).optional().catch(undefined);

/** `/search?…` — what the header / hero searched and the filters picked on the page; anything malformed is dropped. */
export const SearchParams = z.object({
  q: z.string().max(100).optional().catch(undefined),
  scope: z.enum(SCOPES).optional().catch(undefined),
  category: z.string().regex(/^[a-z0-9_.-]{1,120}$/).optional().catch(undefined),
  sort: z.enum(SORTS).optional().catch(undefined),
  tier: z.enum(['registered', 'trusted', 'master']).optional().catch(undefined),
  maxPrice: z.coerce.number().int().min(0).max(10_000_000).optional().catch(undefined),
  delivery: z.literal('tonight').optional().catch(undefined),
  openNow: flag,
  instantBook: flag,
  dietary: list,
  allergenFree: list,
  radiusKm: z.coerce.number().int().min(1).max(100).optional().catch(undefined),
});
export type SearchParams = z.infer<typeof SearchParams>;

/** The filter parameters (everything but the text, scope and sort): "Clear all" removes these. */
export const FILTER_KEYS = ['category', 'tier', 'maxPrice', 'delivery', 'openNow', 'instantBook', 'dietary', 'allergenFree', 'radiusKm'] as const;

/** Where the visitor is, when the browser knows it: province code (the API's `market`) and coordinates. */
export interface Place { market?: string; lat?: number; lng?: number }

const KIND: Record<Exclude<Scope, 'all'>, string> = { services: 'service', shop: 'product', food: 'food' };

/**
 * The API's query string for the page's parameters and the visitor's place. Distance sort and radius need
 * coordinates: without them they are left out (the API would refuse them) and the page says so.
 */
export function apiQuery(p: SearchParams, place: Place | null, locale: Locale): string {
  const qs = new URLSearchParams();
  if (p.q?.trim()) qs.set('q', p.q.trim());
  if (place?.market) qs.set('market', place.market);
  qs.set('lang', locale);
  if (p.scope && p.scope !== 'all') qs.set('kind', KIND[p.scope]);
  if (p.category) qs.set('category', p.category);
  if (p.tier) qs.set('tier', p.tier);
  if (p.maxPrice !== undefined) qs.set('maxPrice', String(p.maxPrice));
  if (p.delivery) qs.set('delivery', p.delivery);
  if (p.openNow) qs.set('openNow', 'true');
  if (p.instantBook) qs.set('instantBook', 'true');
  if (p.dietary) qs.set('dietary', p.dietary);
  if (p.allergenFree) qs.set('allergenFree', p.allergenFree);
  const located = place?.lat !== undefined && place.lng !== undefined;
  if (located) { qs.set('lat', place.lat!.toFixed(5)); qs.set('lng', place.lng!.toFixed(5)); }
  if (located && p.radiusKm) qs.set('radiusKm', String(p.radiusKm));
  const sort = p.sort === 'distance' && !located ? undefined : p.sort;
  if (sort && sort !== 'relevance') qs.set('sort', sort);
  qs.set('size', String(PAGE_SIZE));
  return qs.toString();
}
export const PAGE_SIZE = 24;

const Merchant = z.object({
  id: z.string(), name: z.string(), type: z.string(), slug: z.string().nullish(), tier: z.string().nullish(),
});
export const SearchItem = z.object({
  id: z.string(),
  kind: z.enum(['service', 'product', 'food', 'merchant']),
  name: z.string(),
  description: z.string().nullish(),
  merchant: Merchant,
  category: z.object({ id: z.string(), name: z.string().nullish() }).nullish(),
  priceCents: z.number().int().nullish(),
  pricingMode: z.string().nullish(),
  rating: z.number().nullish(),
  reviewCount: z.number().int().nullish(),
  trustTier: z.string().nullish(),
  distanceKm: z.number().nullish(),
  instantBook: z.boolean().nullish(),
  fulfilment: z.array(z.string()).nullish(),
  openNow: z.boolean().nullish(),
  soldOut: z.boolean().nullish(),
  onTonightsRun: z.boolean().nullish(),
  prepMinutes: z.number().int().nullish(),
  dietary: z.array(z.string()).nullish(),
  imageKey: z.string().nullish(),
});
export type SearchItem = z.infer<typeof SearchItem>;

const Facet = z.object({ value: z.string(), label: z.string().nullish(), count: z.number().int() });
export type Facet = z.infer<typeof Facet>;
const Facets = z.object({
  kinds: z.array(Facet).default([]), categories: z.array(Facet).default([]), merchants: z.array(Facet).default([]),
  tiers: z.array(Facet).default([]), prices: z.array(Facet).default([]), dietary: z.array(Facet).default([]),
});
export const SearchPage = z.object({
  items: z.array(SearchItem),
  total: z.number().int(),
  facets: Facets.nullish(),
  next: z.string().nullish(),
});
export type SearchPage = z.infer<typeof SearchPage>;

/**
 * One search, page by page (`after` = the previous page's `next`). The key is the API query, so the server-rendered
 * first page (no place) hydrates as is, and the browser's located search is another entry.
 */
export const searchQuery = (query: string) => infiniteQueryOptions({
  queryKey: ['search', query],
  queryFn: ({ pageParam, signal }) => {
    const xff = forwardedFor();
    return http(`/api/v1/search?${query}${pageParam ? `&after=${encodeURIComponent(pageParam)}` : ''}`,
      { signal, ...(xff ? { headers: { 'x-forwarded-for': xff } } : {}) }, SearchPage);
  },
  initialPageParam: '' as string,
  getNextPageParam: last => last.next ?? undefined,
  staleTime: 30_000,
});

export const Suggestion = z.object({
  text: z.string(),
  type: z.enum(['service', 'product', 'food', 'merchant', 'category']),
  id: z.string(),
  merchantId: z.string().nullish(),
  merchantName: z.string().nullish(),
  merchantType: z.string().nullish(),
  merchantSlug: z.string().nullish(),
  priceCents: z.number().int().nullish(),
  trustTier: z.string().nullish(),
  rating: z.number().nullish(),
  highlight: z.array(z.object({ start: z.number().int(), length: z.number().int() })).default([]),
});
export type Suggestion = z.infer<typeof Suggestion>;
const Suggestions = z.object({ items: z.array(Suggestion) });

/** Predictions for what has been typed (browser only; the header and the home hero). */
export const suggestQuery = (q: string, market: string | undefined, locale: Locale) => queryOptions({
  queryKey: ['search', 'suggest', q.trim().toLowerCase(), market ?? '', locale],
  queryFn: ({ signal }) => {
    const qs = new URLSearchParams({ q: q.trim(), lang: locale, size: '6' });
    if (market) qs.set('market', market);
    return http(`/api/v1/search/suggest?${qs}`, { signal }, Suggestions);
  },
  staleTime: 30_000,
});

/** The province code of a location, when it is one (the API's `market`). */
export const marketOf = (province: string | undefined) => (province && /^[A-Z]{2}$/.test(province) ? province : undefined);

const leaf = (categoryId: string) => categoryId.split('.').pop() ?? categoryId;

/** Where a business's page is: providers and kitchens have one; a shop's goods are searched by its name. */
export function merchantHref(m: { type?: string | null; slug?: string | null; name?: string | null }): string {
  if (m.slug && (m.type === 'provider' || m.type === 'both')) return `/providers/${m.slug}`;
  if (m.slug && m.type === 'kitchen') return `/food/${m.slug}`;
  return `/search?${new URLSearchParams({ scope: 'shop', q: m.name ?? '' })}`;
}

/** The page a result opens: a product (by its offer, S-50 redirects to the product), a provider, a kitchen. */
export function itemHref(item: SearchItem): string {
  switch (item.kind) {
    case 'product': return `/products/${item.id}?offer=${item.id}`;
    case 'merchant': return merchantHref(item.merchant);
    case 'food': return item.merchant.slug ? `/food/${item.merchant.slug}` : merchantHref(item.merchant);
    case 'service': return item.merchant.slug ? `/providers/${item.merchant.slug}` : merchantHref(item.merchant);
  }
}

/** A category's landing page: services and shop departments have one (S-53, S-49); others search within it. */
export function categoryHref(categoryId: string): string {
  if (categoryId.startsWith('service.')) return `/services/${leaf(categoryId)}`;
  if (categoryId.startsWith('shop.')) return `/shop/${leaf(categoryId)}`;
  return `/search?category=${encodeURIComponent(categoryId)}`;
}

export function suggestionHref(s: Suggestion): string {
  switch (s.type) {
    case 'product': return `/products/${s.id}?offer=${s.id}`;
    case 'category': return categoryHref(s.id);
    case 'merchant': return merchantHref({ type: s.merchantType, slug: s.merchantSlug, name: s.text });
    case 'food': return s.merchantSlug ? `/food/${s.merchantSlug}` : `/search?q=${encodeURIComponent(s.text)}`;
    case 'service': return s.merchantSlug ? `/providers/${s.merchantSlug}` : `/search?q=${encodeURIComponent(s.text)}`;
  }
}

/** A catalogue image (`media:<id>`) has a public URL; dish photos (`object:`) don't yet (S-44 decision). */
export const imageUrl = (key: string | null | undefined) =>
  key?.startsWith('media:') ? `/api/v1/public/catalogue/media/${encodeURIComponent(key.slice('media:'.length))}` : null;
