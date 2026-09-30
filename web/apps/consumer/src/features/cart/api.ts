import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import type { Locale } from '@northline/ui';

/**
 * The cart (S-51; docs/CONSUMER_WEB_PLAN.md § Guest id and cart): `GET /api/v1/cart` → the multi-shop cart of the
 * signed-in person, or of the guest (X-Northline-Guest, added by the consumer-bff; a signed-in call merges the guest's
 * cart first). The header reads `itemCount` (`cartQuery`, key `['cart']`); every cart mutation updates it. Checkout
 * (`/api/v1/me/checkout…`) is for signed-in people.
 */
export const CartLine = z.object({
  itemId: z.string(), merchantId: z.string(), offerId: z.string(), variantId: z.string().nullish(), productId: z.string(),
  name: z.string(), option: z.string().nullish(), unit: z.string().nullish(), imageUrl: z.string().nullish(),
  unitCents: z.number().int(), qty: z.number().int(), lineCents: z.number().int(), stock: z.number().int(),
  available: z.boolean(), handlingDays: z.number().int().nullish(),
});
export type CartLine = z.infer<typeof CartLine>;
export const Cart = z.object({
  itemCount: z.number().int().nonnegative(), shopCount: z.number().int().optional().default(0), subtotalCents: z.number().int().optional().default(0),
  groups: z.array(z.object({ merchantId: z.string(), shopName: z.string(), items: z.array(CartLine) })).optional().default([]),
}).loose();
export type Cart = z.infer<typeof Cart>;
/** What the header needs (kept for S-45's contract). */
export const CartSummary = Cart;
export type CartSummary = Cart;
const EMPTY: Cart = { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] };

export const cartQuery = queryOptions({
  queryKey: ['cart'],
  queryFn: async (): Promise<Cart> => {
    try { return await http('/api/v1/cart', {}, Cart); } catch (e) { if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return EMPTY; throw e; }
  },
  staleTime: 30_000,
});

/** Items in the cart for the header badge; 0 while loading or when the cart can't be read. */
export function useCartCount(enabled = true) {
  const q = useQuery({ ...cartQuery, enabled });
  return q.data?.itemCount ?? 0;
}

/** Change a line's quantity (0 removes it); the answer is the new cart. */
export function useChangeCartLine() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ itemId, qty }: { itemId: string; qty: number }) =>
      http(`/api/v1/cart/items/${encodeURIComponent(itemId)}`, qty === 0 ? { method: 'DELETE' } : { method: 'PATCH', body: { qty } }, Cart),
    onSuccess: cart => { qc.setQueryData(cartQuery.queryKey, cart); void qc.invalidateQueries({ queryKey: ['checkout'] }); },
  });
}

// ── checkout ─────────────────────────────────────────────────────────────────────────────────────────────────────

export const AddressView = z.object({ id: z.string(), street: z.string(), unit: z.string().nullish(), city: z.string(), province: z.string(), postal: z.string(), note: z.string().nullish(), isDefault: z.boolean() });
export type AddressView = z.infer<typeof AddressView>;
export const DeliveryOption = z.object({
  id: z.string(), kind: z.enum(['pooled', 'direct']), windowId: z.string().nullish(), day: z.enum(['today', 'tomorrow', 'later']).nullish(),
  startsAt: z.string().nullish(), endsAt: z.string().nullish(), orderBy: z.string().nullish(), packBy: z.string().nullish(),
  feeCents: z.number().int(), households: z.number().int(), etaMinutes: z.number().int().nullish(),
});
export type DeliveryOption = z.infer<typeof DeliveryOption>;
export const Payment = z.object({ provider: z.enum(['stripe', 'fake']), publishableKey: z.string().nullish() });
export const Setup = z.object({
  cart: Cart, addresses: z.array(AddressView), options: z.array(DeliveryOption), payment: Payment,
  stepUp: z.enum(['none', 'required', 'enrol']), market: z.string(), served: z.boolean(),
});
export type Setup = z.infer<typeof Setup>;
export const TaxLine = z.object({ type: z.string(), percent: z.number(), cents: z.number().int() });
export const Quote = z.object({ subtotalCents: z.number().int(), deliveryFeeCents: z.number().int(), taxCents: z.number().int(), taxes: z.array(TaxLine), totalCents: z.number().int(), market: z.string() });
export type Quote = z.infer<typeof Quote>;
export const Intent = z.object({ paymentIntent: z.string(), clientSecret: z.string().nullish(), status: z.string(), amountCents: z.number().int() });
export type Intent = z.infer<typeof Intent>;
export const Started = z.object({ checkoutId: z.string(), orderId: z.string(), ref: z.string(), totalCents: z.number().int(), expiresAt: z.string(), payment: Payment, intents: z.array(Intent) });
export type Started = z.infer<typeof Started>;
export const Placed = z.object({ orderId: z.string(), ref: z.string() });

export interface AddressInput { addressId?: string; street?: string; unit?: string; city?: string; province?: string; postal?: string; note?: string }
export interface CheckoutBody { kind: 'pooled' | 'direct' | ''; windowId?: string | null; address: AddressInput; substitution: 'similar' | 'refund' | 'ask' }

export const setupQuery = (market: string, locale: Locale, enabled: boolean) => queryOptions({
  queryKey: ['checkout', 'setup', market.toLowerCase(), locale],
  queryFn: () => http(`/api/v1/me/checkout?market=${encodeURIComponent(market)}&lang=${locale}`, {}, Setup),
  enabled,
  staleTime: 15_000,
});

export const quoteQuery = (body: CheckoutBody, locale: Locale, enabled: boolean) => queryOptions({
  queryKey: ['checkout', 'quote', body, locale],
  queryFn: () => http(`/api/v1/me/checkout/quote?lang=${locale}`, { method: 'POST', body }, Quote),
  enabled,
  staleTime: 30_000,
  retry: false,
});

/** Money-moving: Idempotency-Key (one per Pay attempt, reused on retry) and, when asked, the step-up proof. */
export const startCheckout = (body: CheckoutBody, key: string, locale: Locale, proof?: string) =>
  http(`/api/v1/me/checkouts?lang=${locale}`, { method: 'POST', body, idempotencyKey: key, headers: proof ? { 'x-step-up': proof } : undefined }, Started);

export const placeOrder = (checkoutId: string, key: string) =>
  http(`/api/v1/me/checkouts/${encodeURIComponent(checkoutId)}/place`, { method: 'POST', idempotencyKey: key }, Placed);

/** `code` of a ProblemDetail answer (step_up_required, out_of_stock, …). */
export const problemCode = (e: unknown) =>
  e instanceof ApiError && e.body && typeof e.body === 'object' && 'code' in e.body ? String((e.body as { code: unknown }).code) : undefined;
