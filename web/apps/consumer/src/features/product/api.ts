import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';
import { Run } from '../shop/api';
import { cartQuery } from '../cart/api';

/**
 * The product page (S-50): `GET /api/v1/public/shop/products/{id}?market=&lang=` — the catalogue record and the
 * market's offers, best first, each with variants, stock and the pooled runs it can make (cut-off computed by the api
 * in America/Edmonton). Public and server-rendered like the other Shop pages.
 */
export const Variant = z.object({ variantId: z.string(), value: z.string(), priceCents: z.number().int(), stock: z.number().int() });
export type Variant = z.infer<typeof Variant>;

export const Offer = z.object({
  offerId: z.string(), merchantId: z.string(), shopName: z.string(), tier: z.string(), rating: z.number(), ratingCount: z.number().int(),
  priceCents: z.number().int(), compareAtCents: z.number().int().nullish(), condition: z.string().nullish(), stock: z.number().int(),
  lowStock: z.boolean(), returnsPolicy: z.string().nullish(), variantTheme: z.string(), variants: z.array(Variant),
  runs: z.array(Run.extend({ packBy: z.string() })), images: z.array(z.string()),
  more: z.array(z.object({ productId: z.string(), name: z.string(), priceCents: z.number().int() })),
});
export type Offer = z.infer<typeof Offer>;

export const ProductPage = z.object({
  productId: z.string(), name: z.string(), brand: z.string().nullish(), description: z.string().nullish(), bullets: z.array(z.string()),
  unit: z.string().nullish(), departmentSlug: z.string(), departmentName: z.string(), market: z.string(), served: z.boolean(),
  offers: z.array(Offer), direct: z.object({ etaMinutes: z.number().int(), feeCents: z.number().int() }).nullish(),
});
export type ProductPage = z.infer<typeof ProductPage>;

export const productQuery = (productId: string, market: string, locale: Locale) => queryOptions({
  queryKey: ['shop', 'product', productId, market.toLowerCase(), locale],
  queryFn: () => http(`/api/v1/public/shop/products/${encodeURIComponent(productId)}?market=${encodeURIComponent(market)}&lang=${locale}`, {}, ProductPage),
  staleTime: 30_000,
});

export const ProductSearch = z.object({
  market: z.string().max(60).optional().catch(undefined),
  offer: z.string().max(40).optional().catch(undefined),
});

/**
 * Add to cart (S-51 contract, docs/CONSUMER_WEB_PLAN.md § Cart): `POST /api/v1/cart/items { offerId, variantId?, qty }`
 * — guests too (the consumer-bff adds the guest id). Refreshes the header's count.
 */
export function useAddToCart() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (item: { offerId: string; variantId?: string; qty: number }) => http('/api/v1/cart/items', { method: 'POST', body: item }),
    onSuccess: () => qc.invalidateQueries({ queryKey: cartQuery.queryKey }),
  });
}
