import { queryOptions, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';

/**
 * Food (S-57). Public reads: `GET /api/v1/public/kitchens?city=&lat=&lng=` (landing, loaded in the browser once the
 * location is known) and `GET /api/v1/public/kitchens/{slug}` (restaurant, server-rendered for SEO). Signed-in:
 * `POST /api/v1/me/food-orders/quote`, `POST /api/v1/me/food-orders` (Idempotency-Key, X-Step-Up), `POST …/{id}/confirm`,
 * `GET …/{id}` (tracking).
 */
export const Card = z.object({
  merchantId: z.string(), slug: z.string().nullish(), name: z.string(),
  cuisines: z.array(z.string()), dietary: z.array(z.string()), priceLevel: z.string(),
  open: z.boolean(), opensAt: z.string().nullish(), closesAt: z.string().nullish(), paused: z.boolean(),
  fulfilment: z.array(z.string()), prepMin: z.number().int(),
  etaFromMin: z.number().int(), etaToMin: z.number().int(), pickupFromMin: z.number().int(), pickupToMin: z.number().int(),
  distanceKm: z.number().nullish(), delivers: z.boolean().nullish(), deliveryFeeCents: z.number().int(),
  rating: z.number(), reviews: z.number().int(), brandColor: z.string().nullish(),
});
export type Card = z.infer<typeof Card>;
export const Kitchens = z.object({ city: z.string(), items: z.array(Card) });

export const Option = z.object({ id: z.string(), name: z.string(), deltaCents: z.number().int(), isDefault: z.boolean(), soldOut: z.boolean() });
export type Option = z.infer<typeof Option>;
export const Group = z.object({
  id: z.string(), name: z.string(), rule: z.enum(['exactly', 'at_least', 'up_to']), count: z.number().int(), required: z.boolean(),
  showForOptionIds: z.array(z.string()), options: z.array(Option),
});
export type Group = z.infer<typeof Group>;
export const Dish = z.object({
  id: z.string(), name: z.string(), description: z.string().nullish(), priceCents: z.number().int(),
  dietary: z.array(z.string()), allergens: z.array(z.string()), soldOut: z.boolean(), availableNow: z.boolean(),
  availability: z.string(), groups: z.array(Group),
});
export type Dish = z.infer<typeof Dish>;
export const Section = z.object({ id: z.string(), name: z.string(), menu: z.string(), items: z.array(Dish) });
export type Section = z.infer<typeof Section>;
export const Combo = z.object({
  id: z.string(), name: z.string(), pricing: z.string(), priceCents: z.number().int().nullish(), discountBps: z.number().int().nullish(),
  fromCents: z.number().int(), saveCents: z.number().int(), availableNow: z.boolean(),
  slots: z.array(z.object({ label: z.string(), qty: z.number().int(), itemIds: z.array(z.string()) })),
});
export type Combo = z.infer<typeof Combo>;
export const Restaurant = z.object({
  kitchen: Card, address: z.string().nullish(), province: z.string().nullish(), ahsVerified: z.boolean(),
  minOrderCents: z.number().int(), serviceFeeBps: z.number().int(), slots: z.array(z.string()),
  sections: z.array(Section), combos: z.array(Combo),
});
export type Restaurant = z.infer<typeof Restaurant>;

const q = (p: Record<string, string | number | undefined | null>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&');

export const kitchensQuery = (city: string, lat?: number, lng?: number) => queryOptions({
  queryKey: ['public', 'kitchens', city.toLowerCase(), lat?.toFixed(3), lng?.toFixed(3)],
  queryFn: () => http(`/api/v1/public/kitchens?${q({ city, lat: lat?.toFixed(5), lng: lng?.toFixed(5) })}`, {}, Kitchens),
  staleTime: 30_000,
});
export const useKitchens = (city: string | undefined, lat?: number, lng?: number) =>
  useQuery({ ...kitchensQuery(city ?? '', lat, lng), enabled: !!city });

export const restaurantQuery = (slug: string) => queryOptions({
  queryKey: ['public', 'kitchen', slug],
  queryFn: () => http(`/api/v1/public/kitchens/${encodeURIComponent(slug)}`, {}, Restaurant),
  staleTime: 30_000,
});

// ── checkout ──────────────────────────────────────────────────────────────────────────────────────────

export const Line = z.object({
  itemId: z.string().nullish(), comboId: z.string().nullish(), title: z.string(), qty: z.number().int(),
  unitCents: z.number().int(), totalCents: z.number().int(), choices: z.array(z.string()), note: z.string().nullish(),
});
export type Line = z.infer<typeof Line>;
export const Totals = z.object({
  kitchen: z.string(), mode: z.string(), scheduledFor: z.string().nullish(), lines: z.array(Line),
  subtotalCents: z.number().int(), deliveryFeeCents: z.number().int(), serviceFeeCents: z.number().int(), tipCents: z.number().int(),
  taxCents: z.number().int(), feeTaxCents: z.number().int(), totalCents: z.number().int(),
  etaFromMin: z.number().int().nullish(), etaToMin: z.number().int().nullish(), estimate: z.boolean(),
});
export type Totals = z.infer<typeof Totals>;
export const FoodStarted = z.object({
  orderId: z.string(), ref: z.string(), totals: Totals, paymentIntent: z.string(), clientSecret: z.string().nullish(),
  status: z.string(), mode: z.enum(['stripe', 'fake']), publishableKey: z.string().nullish(),
});
export type FoodStarted = z.infer<typeof FoodStarted>;
export const Placed = z.object({ orderId: z.string(), ref: z.string() });
export const Tracking = z.object({
  orderId: z.string(), ref: z.string(), kitchen: z.string(), kitchenSlug: z.string().nullish(), mode: z.string(),
  state: z.string(), stage: z.enum(['paid', 'cooking', 'ready', 'on_the_way', 'delivered', 'refunded', 'cancelled']),
  placedAt: z.string(), scheduledFor: z.string().nullish(), acceptedAt: z.string().nullish(), prepMin: z.number().int().nullish(),
  readyBy: z.string().nullish(), readyAt: z.string().nullish(), handedOffAt: z.string().nullish(), deliveredAt: z.string().nullish(),
  eta: z.string().nullish(), totalCents: z.number().int(), lines: z.array(Line),
});
export type Tracking = z.infer<typeof Tracking>;

export interface OrderBody {
  merchantId: string;
  mode: 'delivery' | 'pickup';
  scheduledFor: string | null;
  items: { itemId: string; qty: number; optionIds: string[]; note?: string | null }[];
  combos: { comboId: string; qty: number; itemIds: string[] }[];
  tip: { kind: 'none' | 'amount' | 'percent'; value: number };
  delivery: null | {
    street: string; unit?: string; city?: string; province: string; postalCode?: string; lat: number; lng: number;
    zoneId?: string; zone?: string; dropoff: 'hand' | 'door' | 'lobby'; note?: string; extras: string[];
  };
}

export const quoteFood = (body: OrderBody) => http('/api/v1/me/food-orders/quote', { method: 'POST', body }, Totals);
export const startFood = (body: OrderBody, key: string, proof?: string) =>
  http('/api/v1/me/food-orders', { method: 'POST', body, idempotencyKey: key, headers: proof ? { 'X-Step-Up': proof } : {} }, FoodStarted);
export const confirmFood = (orderId: string, key: string) =>
  http(`/api/v1/me/food-orders/${encodeURIComponent(orderId)}/confirm`, { method: 'POST', idempotencyKey: key }, Placed);

export const trackingQuery = (orderId: string) => queryOptions({
  queryKey: ['me', 'food-orders', orderId],
  queryFn: () => http(`/api/v1/me/food-orders/${encodeURIComponent(orderId)}`, {}, Tracking),
  refetchInterval: 15_000,
});

/** The ProblemDetail `code` of a failed call (409/403), if any. */
export const problemCode = (e: unknown) =>
  e instanceof ApiError && e.body && typeof e.body === 'object' && 'code' in e.body ? String((e.body as { code: unknown }).code) : undefined;
