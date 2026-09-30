import { queryOptions, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';

/**
 * The cart (S-51 owns it; contract in docs/CONSUMER_WEB_PLAN.md § Cart): `GET /api/v1/cart` → the multi-shop cart of
 * the signed-in person, or of the guest (X-Northline-Guest, added by the consumer-bff). The header only needs
 * `itemCount`. Until the api has the endpoint (404) — or for a caller it refuses (401) — the cart counts as empty.
 * Every cart mutation invalidates `cartQuery.queryKey`.
 */
export const CartSummary = z.object({ itemCount: z.number().int().nonnegative() }).loose();
export type CartSummary = z.infer<typeof CartSummary>;

export const cartQuery = queryOptions({
  queryKey: ['cart'],
  queryFn: async (): Promise<CartSummary> => {
    try { return await http('/api/v1/cart', {}, CartSummary); } catch (e) { if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return { itemCount: 0 }; throw e; }
  },
  staleTime: 30_000,
});

/** Items in the cart for the header badge; 0 while loading or when the cart can't be read. */
export function useCartCount(enabled = true) {
  const q = useQuery({ ...cartQuery, enabled });
  return q.data?.itemCount ?? 0;
}
