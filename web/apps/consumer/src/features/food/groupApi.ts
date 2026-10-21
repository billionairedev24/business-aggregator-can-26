import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import { FoodStarted, Tracking, type OrderBody } from './api';

/**
 * Group food orders (S-159), signed in: `POST /api/v1/me/group-orders`, `GET …/join/{code}` (the join page), `POST
 * …/join`, `GET …/{id}`, dishes (`POST …/{id}/items`, `DELETE …/{id}/items/{itemId}`), `…/leave`, the host's
 * `…/lock`, `…/unlock`, `…/cancel`, paying: the host for everyone (`…/checkout` + `…/confirm`, S-57's card payment) or
 * each share (`…/shares/{userId}/pay` + `…/confirm`), then `…/place`.
 */
export const GroupItem = z.object({
  id: z.string(), itemId: z.string().nullish(), comboId: z.string().nullish(), title: z.string(), qty: z.number().int(),
  unitCents: z.number().int(), totalCents: z.number().int(), choices: z.array(z.string()), note: z.string().nullish(),
});
export type GroupItem = z.infer<typeof GroupItem>;
export const Share = z.object({
  subtotalCents: z.number().int(), deliveryFeeCents: z.number().int(), serviceFeeCents: z.number().int(), feeTaxCents: z.number().int(),
  taxCents: z.number().int(), tipCents: z.number().int(), totalCents: z.number().int(),
  state: z.enum(['due', 'paying', 'authorized', 'voided']), paidBy: z.string().nullish(),
});
export type Share = z.infer<typeof Share>;
export const Member = z.object({
  userId: z.string(), name: z.string(), host: z.boolean(), items: z.array(GroupItem), subtotalCents: z.number().int(), share: Share.nullish(),
});
export type Member = z.infer<typeof Member>;
export const GroupView = z.object({
  id: z.string(), code: z.string(), state: z.enum(['open', 'locked', 'paying', 'placed', 'cancelled', 'expired']),
  payment: z.enum(['host', 'split']), merchantId: z.string(), kitchen: z.string(), kitchenSlug: z.string().nullish(),
  mode: z.enum(['delivery', 'pickup']), scheduledFor: z.string().nullish(), area: z.string().nullish(),
  hostId: z.string(), you: z.string(), host: z.boolean(), members: z.array(Member), subtotalCents: z.number().int(), groupMax: z.number().int(),
  tip: z.object({ kind: z.enum(['none', 'amount', 'percent']), value: z.number().int() }),
  orderId: z.string().nullish(), ref: z.string().nullish(), canPlace: z.boolean(), tracking: Tracking.nullish(),
  createdAt: z.string(), expiresAt: z.string(),
});
export type GroupView = z.infer<typeof GroupView>;
export const Preview = z.object({
  code: z.string(), kitchen: z.string(), kitchenSlug: z.string().nullish(), hostFirstName: z.string(), state: z.string(),
  people: z.number().int(), groupMax: z.number().int(), groupId: z.string().nullish(),
});
export type Preview = z.infer<typeof Preview>;
export const ShareStarted = z.object({
  shareId: z.string(), totalCents: z.number().int(), paymentIntent: z.string(), clientSecret: z.string().nullish(), status: z.string(),
  mode: z.enum(['stripe', 'fake']), publishableKey: z.string().nullish(),
});
export type ShareStarted = z.infer<typeof ShareStarted>;

export type Tip = OrderBody['tip'];
export interface StartGroup {
  merchantId: string;
  mode: 'delivery' | 'pickup';
  scheduledFor: string | null;
  payment: 'host' | 'split';
  delivery: OrderBody['delivery'];
}
export interface GroupDish { itemId?: string; comboId?: string; qty: number; optionIds?: string[]; itemIds?: string[]; note?: string | null }

const base = '/api/v1/me/group-orders';
const g = (id: string) => `${base}/${encodeURIComponent(id)}`;

/** The share link guests open (the code is also typed on the same page). */
export const groupPath = (code: string) => `/food/group/${encodeURIComponent(code)}`;

export const previewQuery = (code: string) => queryOptions({
  queryKey: ['me', 'group-preview', code.toUpperCase()],
  queryFn: () => http(`${base}/join/${encodeURIComponent(code)}`, {}, Preview),
});
export const groupQuery = (id: string) => queryOptions({
  queryKey: ['me', 'group-orders', id],
  queryFn: () => http(g(id), {}, GroupView),
  // guests and the host follow each other's changes (and the payments) without a stream
  refetchInterval: 5_000,
});

export const startGroup = (body: StartGroup) => http(base, { method: 'POST', body }, GroupView);
export const joinGroup = (code: string) => http(`${base}/join`, { method: 'POST', body: { code } }, GroupView);
export const addToGroup = (id: string, dish: GroupDish) => http(`${g(id)}/items`, { method: 'POST', body: dish }, GroupView);
export const removeFromGroup = (id: string, itemId: string) => http(`${g(id)}/items/${encodeURIComponent(itemId)}`, { method: 'DELETE' }, GroupView);
export const leaveGroup = (id: string) => http(`${g(id)}/leave`, { method: 'POST' }, GroupView);
export const lockGroup = (id: string, tip: Tip) => http(`${g(id)}/lock`, { method: 'POST', body: { tip } }, GroupView);
export const unlockGroup = (id: string) => http(`${g(id)}/unlock`, { method: 'POST' }, GroupView);
export const cancelGroup = (id: string) => http(`${g(id)}/cancel`, { method: 'POST' }, GroupView);
export const placeGroup = (id: string) => http(`${g(id)}/place`, { method: 'POST' }, GroupView);
export const checkoutGroup = (id: string, tip: Tip, key: string, proof?: string) =>
  http(`${g(id)}/checkout`, { method: 'POST', body: { tip }, idempotencyKey: key, headers: proof ? { 'x-step-up': proof } : undefined }, FoodStarted);
export const confirmGroup = (id: string, key: string) => http(`${g(id)}/confirm`, { method: 'POST', idempotencyKey: key }, GroupView);
export const payShare = (id: string, userId: string, key: string, proof?: string) =>
  http(`${g(id)}/shares/${encodeURIComponent(userId)}/pay`, { method: 'POST', idempotencyKey: key, headers: proof ? { 'x-step-up': proof } : undefined }, ShareStarted);
export const confirmShare = (id: string, userId: string, key: string) =>
  http(`${g(id)}/shares/${encodeURIComponent(userId)}/confirm`, { method: 'POST', idempotencyKey: key }, GroupView);
