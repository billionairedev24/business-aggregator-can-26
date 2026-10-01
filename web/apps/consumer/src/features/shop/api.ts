import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';

/**
 * Public Shop reads (S-49; `GET /api/v1/public/shop…`, guests allowed, the same for everyone): the landing page and a
 * department. The market (a city) and the language are query parameters, so the server-rendered page and its cache
 * key are the URL. Loaders `ensureQueryData`, screens `useSuspenseQuery` (docs/CONSUMER_WEB_PLAN.md § Data loading).
 */
export const Run = z.object({
  windowId: z.string(),
  label: z.string().nullish(),
  day: z.enum(['today', 'tomorrow', 'later']),
  startsAt: z.string(),
  endsAt: z.string(),
  orderBy: z.string(),
  feeCents: z.number().int(),
  households: z.number().int(),
});
export type Run = z.infer<typeof Run>;

export const DepartmentTile = z.object({ slug: z.string(), name: z.string(), shops: z.number().int() });
export type DepartmentTile = z.infer<typeof DepartmentTile>;

export const ShopCard = z.object({
  merchantId: z.string(), name: z.string(), tier: z.string(), departmentSlug: z.string(), departmentName: z.string(),
  products: z.number().int(), run: Run.nullish(),
});
export type ShopCard = z.infer<typeof ShopCard>;

export const ProductCard = z.object({
  productId: z.string(), offerId: z.string(), name: z.string(), merchantId: z.string(), shopName: z.string(),
  unit: z.string().nullish(), priceCents: z.number().int(), imageUrl: z.string().nullish(), sellers: z.number().int(), run: Run.nullish(),
});
export type ProductCard = z.infer<typeof ProductCard>;

export const Landing = z.object({
  market: z.string(), served: z.boolean(), run: Run.nullish(), shopCount: z.number().int(),
  departments: z.array(DepartmentTile), shops: z.array(ShopCard), popular: z.array(ProductCard),
});
export type Landing = z.infer<typeof Landing>;

export const Department = z.object({
  slug: z.string(), name: z.string(), groupName: z.string(), market: z.string(), served: z.boolean(), run: Run.nullish(),
  siblings: z.array(DepartmentTile), shopCount: z.number().int(), onRunCount: z.number().int(), productCount: z.number().int(),
  shops: z.array(ShopCard), products: z.array(ProductCard),
});
export type Department = z.infer<typeof Department>;

/**
 * `market` = a city; undefined = nobody chose one, and the api renders its fallback market (the default province's
 * first live market — region configuration, never a city in this app; the response names it in `market`).
 */
export const marketParams = (market: string | undefined, locale: Locale) =>
  `${market ? `market=${encodeURIComponent(market)}&` : ''}lang=${locale}`;
const q = marketParams;
const key = (market: string | undefined) => market?.toLowerCase() ?? '';

export const landingQuery = (market: string | undefined, locale: Locale) => queryOptions({
  queryKey: ['shop', 'landing', key(market), locale],
  queryFn: () => http(`/api/v1/public/shop?${q(market, locale)}`, {}, Landing),
  staleTime: 60_000,
});

export const departmentQuery = (slug: string, market: string | undefined, locale: Locale) => queryOptions({
  queryKey: ['shop', 'department', slug, key(market), locale],
  queryFn: () => http(`/api/v1/public/shop/departments/${encodeURIComponent(slug)}?${q(market, locale)}`, {}, Department),
  staleTime: 60_000,
});

/** `?market=` of the Shop routes: absent means the default market. */
export const MarketSearch = z.object({ market: z.string().max(60).optional().catch(undefined) });
export type MarketSearch = z.infer<typeof MarketSearch>;
