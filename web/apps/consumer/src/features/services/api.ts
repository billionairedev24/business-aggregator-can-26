import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';

/**
 * Services journey reads (S-53): public, cacheable, the same for everyone.
 * `GET /api/v1/public/services` (landing), `GET /api/v1/public/services/{slug}` (category) — loaded on the server;
 * `GET /api/v1/public/services/{slug}/providers?lat&lng&city` — depends on the customer's location, loaded in the browser.
 */
export const ServiceKind = z.enum(['visit', 'home', 'event', 'appointment', 'consult']);
export type ServiceKind = z.infer<typeof ServiceKind>;

/** `name_i18n`: English always, French once the taxonomy is translated. */
export const Names = z.record(z.string(), z.string());
export type Names = z.infer<typeof Names>;

export const ServiceItem = z.object({ slug: z.string(), names: Names, kind: ServiceKind, providers: z.number().int() });
export type ServiceItem = z.infer<typeof ServiceItem>;

export const ServiceGroup = z.object({
  id: z.string(), key: z.string(), names: Names, note: z.string().nullish(), items: z.array(ServiceItem),
});
export type ServiceGroup = z.infer<typeof ServiceGroup>;

export const ServicesLanding = z.object({ liveCategories: z.number().int(), providers: z.number().int(), groups: z.array(ServiceGroup) });
export type ServicesLanding = z.infer<typeof ServicesLanding>;

export const PricingMode = z.enum(['fixed', 'hourly', 'quote']);
export type PricingMode = z.infer<typeof PricingMode>;

export const Job = z.object({
  name: z.string(), included: z.string().nullish(), pricingMode: PricingMode, priceCents: z.number().nullish(), durationMin: z.number().int(),
});
export type Job = z.infer<typeof Job>;

export const ServiceCategory = z.object({
  id: z.string(), slug: z.string(), names: Names, group: ServiceGroup, kind: ServiceKind, vehicle: z.boolean(),
  regulatedRegistry: z.string().nullish(), providers: z.number().int(), quoteable: z.boolean(), jobs: z.array(Job),
});
export type ServiceCategory = z.infer<typeof ServiceCategory>;

export const ProviderCard = z.object({
  merchantId: z.string(), slug: z.string(), name: z.string(), tier: z.string(), brandColor: z.string(),
  blurb: z.string().nullish(), rating: z.number(), reviewCount: z.number().int(),
  onTimePct: z.number().nullish(), disputePct: z.number().nullish(), rebookPct: z.number().nullish(),
  fromCents: z.number().nullish(), pricingMode: PricingMode, instantBook: z.boolean(),
  nextAvailable: z.string().nullish(), zones: z.array(z.string()),
});
export type ProviderCard = z.infer<typeof ProviderCard>;

export const Providers = z.object({
  categorySlug: z.string(), kind: ServiceKind, area: z.string().nullish(), city: z.string(), items: z.array(ProviderCard),
});
export type Providers = z.infer<typeof Providers>;

export const servicesLandingQuery = (locale: Locale) => queryOptions({
  queryKey: ['services', 'landing', locale],
  queryFn: () => http(`/api/v1/public/services?lang=${locale}`, {}, ServicesLanding),
  staleTime: 60_000,
});

export const serviceCategoryQuery = (slug: string, locale: Locale) => queryOptions({
  queryKey: ['services', 'category', slug, locale],
  queryFn: () => http(`/api/v1/public/services/${encodeURIComponent(slug)}?lang=${locale}`, {}, ServiceCategory),
  staleTime: 60_000,
});

/** Where the list is for: device coordinates when the customer shared them, else the city of the location pill. */
export interface ProviderPlace { lat?: number; lng?: number; city?: string }

export const providersQuery = (slug: string, place: ProviderPlace, locale: Locale) => queryOptions({
  queryKey: ['services', 'providers', slug, place, locale],
  queryFn: () => {
    const q = new URLSearchParams({ lang: locale });
    if (place.lat !== undefined && place.lng !== undefined) { q.set('lat', place.lat.toFixed(5)); q.set('lng', place.lng.toFixed(5)); }
    if (place.city) q.set('city', place.city);
    return http(`/api/v1/public/services/${encodeURIComponent(slug)}/providers?${q}`, {}, Providers);
  },
  staleTime: 30_000,
});

/** A name in the reader's language; `lang` is set when it had to fall back to English (for `<span lang>`). */
export function localName(names: Names, locale: Locale): { text: string; lang?: 'en' } {
  const own = names[locale];
  if (own) return { text: own };
  return { text: names.en ?? Object.values(names)[0] ?? '', lang: locale === 'en' ? undefined : 'en' };
}
