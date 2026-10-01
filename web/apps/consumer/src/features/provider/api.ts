import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';
import { Names, PricingMode, ServiceKind } from '../services/api';

/**
 * The public provider page (S-54): the storefront API for the page itself — `GET /api/v1/storefronts/{slug}`
 * (published sections in order, brand colour, logo, tagline, announcement, CTA label, verified facts) — plus
 * `GET /api/v1/public/providers/{slug}` (trust figures, services, service area, next slot, first reviews) and
 * `GET /api/v1/public/providers/{slug}/reviews?offset=` ("Show more reviews"). All public, rendered on the server.
 */
export const SectionKind = z.enum(['hero', 'cta', 'about', 'services', 'reviews', 'area', 'gallery', 'faq', 'featured', 'catalogue', 'delivery', 'policies', 'menu', 'hours', 'fulfil', 'permit']);
export type SectionKind = z.infer<typeof SectionKind>;

export const Storefront = z.object({
  slug: z.string(), url: z.string(), pageKind: z.string(), brandColor: z.string(), logoUrl: z.string().nullish(),
  tagline: z.string().nullish(), ctaLabel: z.enum(['book_visit', 'request_quote', 'order_now', 'reserve']), announcement: z.string().nullish(),
  customDomain: z.string().nullish(), publishedAt: z.string(),
  sections: z.array(z.object({ kind: z.string(), settings: z.record(z.string(), z.unknown()).default({}) })),
  business: z.object({
    displayName: z.string(), type: z.string(), tier: z.string().nullish(), city: z.string().nullish(), about: z.string().nullish(),
    serviceArea: z.string().nullish(), verifiedFacts: z.array(z.string()).default([]),
  }).loose(),
});
export type Storefront = z.infer<typeof Storefront>;

export const PublicReview = z.object({
  id: z.string(), rating: z.number().int(), text: z.string().nullish(), author: z.string().nullish(), jobLabel: z.string().nullish(),
  refType: z.string(), createdAt: z.string(), reply: z.string().nullish(),
});
export type PublicReview = z.infer<typeof PublicReview>;
export const ReviewPage = z.object({ items: z.array(PublicReview), nextOffset: z.number().int().nullish() });
export type ReviewPage = z.infer<typeof ReviewPage>;

export const ProviderService = z.object({
  id: z.string(), name: z.string(), included: z.string().nullish(), pricingMode: PricingMode, priceCents: z.number().nullish(),
  durationMin: z.number().int(), instantBook: z.boolean(), categorySlug: z.string().nullish(), kind: ServiceKind,
});
export type ProviderService = z.infer<typeof ProviderService>;

export const ProviderFacts = z.object({
  merchantId: z.string(), slug: z.string(), name: z.string(), tier: z.string(), city: z.string().nullish(), since: z.string(),
  verifiedFacts: z.array(z.string()), rating: z.number(), reviewCount: z.number().int(),
  onTimePct: z.number().nullish(), disputePct: z.number().nullish(), rebookPct: z.number().nullish(),
  kind: ServiceKind, category: z.object({ id: z.string(), slug: z.string(), names: Names }).nullish(), vehicle: z.boolean(), quoteable: z.boolean(),
  services: z.array(ProviderService), zones: z.array(z.string()), nextAvailable: z.string().nullish(), taxBps: z.number().int().catch(0), reviews: ReviewPage,
});
export type ProviderFacts = z.infer<typeof ProviderFacts>;

export const storefrontQuery = (slug: string) => queryOptions({
  queryKey: ['storefront', slug],
  queryFn: () => http(`/api/v1/storefronts/${encodeURIComponent(slug)}`, {}, Storefront),
  staleTime: 60_000,
});

export const providerQuery = (slug: string, locale: Locale) => queryOptions({
  queryKey: ['provider', slug, locale],
  queryFn: () => http(`/api/v1/public/providers/${encodeURIComponent(slug)}?lang=${locale}`, {}, ProviderFacts),
  staleTime: 60_000,
});

export const PAGE_SIZE = 10;

/** Reviews after the first three the page shows. */
export const moreReviewsQuery = (slug: string, first: number) => infiniteQueryOptions({
  queryKey: ['provider', slug, 'reviews', first],
  initialPageParam: first,
  queryFn: ({ pageParam }) => http(`/api/v1/public/providers/${encodeURIComponent(slug)}/reviews?offset=${pageParam}&limit=${PAGE_SIZE}`, {}, ReviewPage),
  getNextPageParam: last => last.nextOffset ?? undefined,
});
