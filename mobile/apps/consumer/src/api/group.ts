import type { ApiClient } from '@northline/mobile-kit';

/**
 * Group food orders (S-159) — the consumer web's endpoints (web/apps/consumer/src/features/food/groupApi.ts), with the
 * app's DPoP tokens. The kitchen's menu is the public restaurant read (`/public/kitchens/{slug}`).
 */
export interface GroupItem { id: string; itemId?: string | null; comboId?: string | null; title: string; qty: number; unitCents: number; totalCents: number; choices: string[]; note?: string | null }
export interface GroupShare { subtotalCents: number; deliveryFeeCents: number; serviceFeeCents: number; feeTaxCents: number; taxCents: number; tipCents: number; totalCents: number; state: 'due' | 'paying' | 'authorized' | 'voided'; paidBy?: string | null }
export interface GroupMember { userId: string; name: string; host: boolean; items: GroupItem[]; subtotalCents: number; share?: GroupShare | null }
export interface GroupView {
  id: string; code: string; state: 'open' | 'locked' | 'paying' | 'placed' | 'cancelled' | 'expired'; payment: 'host' | 'split';
  merchantId: string; kitchen: string; kitchenSlug?: string | null; mode: 'delivery' | 'pickup'; area?: string | null;
  hostId: string; you: string; host: boolean; members: GroupMember[]; subtotalCents: number; groupMax: number;
  tip: { kind: 'none' | 'amount' | 'percent'; value: number }; orderId?: string | null; ref?: string | null; canPlace: boolean;
  tracking?: { stage: string } | null; expiresAt: string;
}
export interface GroupPreview { code: string; kitchen: string; kitchenSlug?: string | null; hostFirstName: string; state: string; people: number; groupMax: number; groupId?: string | null }
export interface ShareStarted { shareId: string; totalCents: number; paymentIntent: string; clientSecret?: string | null; status: string; mode: 'stripe' | 'fake'; publishableKey?: string | null }
export interface FoodStarted { orderId: string; ref: string; totals: { totalCents: number }; paymentIntent: string; clientSecret?: string | null; status: string; mode: 'stripe' | 'fake'; publishableKey?: string | null }
export interface MenuOption { id: string; name: string; deltaCents: number; isDefault: boolean; soldOut: boolean }
export interface MenuGroup { id: string; name: string; rule: 'exactly' | 'at_least' | 'up_to'; count: number; required: boolean; showForOptionIds: string[]; options: MenuOption[] }
export interface MenuDish { id: string; name: string; description?: string | null; priceCents: number; soldOut: boolean; availableNow: boolean; groups: MenuGroup[] }
export interface Restaurant { kitchen: { name: string }; sections: Array<{ id: string; name: string; items: MenuDish[] }> }
export type Tip = GroupView['tip'];

const g = (id: string) => `/me/group-orders/${encodeURIComponent(id)}`;
/** Every group answer has a body (204 never). */
async function need<T>(p: Promise<T | null>): Promise<T> {
  const v = await p;
  if (v === null) throw new Error('empty answer');
  return v;
}

export const groupApi = (api: ApiClient) => ({
  preview: (code: string) => need(api.get<GroupPreview>(`/me/group-orders/join/${encodeURIComponent(code)}`)),
  view: (id: string) => need(api.get<GroupView>(g(id))),
  join: (code: string) => need(api.post<GroupView>('/me/group-orders/join', { json: { code } })),
  menu: (slug: string) => need(api.get<Restaurant>(`/public/kitchens/${encodeURIComponent(slug)}`, { auth: 'optional' })),
  add: (id: string, dish: { itemId: string; qty: number; optionIds: string[] }) => need(api.post<GroupView>(`${g(id)}/items`, { json: dish })),
  remove: (id: string, itemId: string) => need(api.delete<GroupView>(`${g(id)}/items/${encodeURIComponent(itemId)}`)),
  leave: (id: string) => need(api.post<GroupView>(`${g(id)}/leave`)),
  lock: (id: string, tip: Tip) => need(api.post<GroupView>(`${g(id)}/lock`, { json: { tip } })),
  unlock: (id: string) => need(api.post<GroupView>(`${g(id)}/unlock`)),
  cancel: (id: string) => need(api.post<GroupView>(`${g(id)}/cancel`)),
  place: (id: string) => need(api.post<GroupView>(`${g(id)}/place`)),
  checkout: (id: string, key: string, proof?: string) => need(api.post<FoodStarted>(`${g(id)}/checkout`, { json: {}, idempotencyKey: key, headers: proof ? { 'X-Step-Up': proof } : undefined })),
  confirm: (id: string, key: string) => need(api.post<GroupView>(`${g(id)}/confirm`, { idempotencyKey: key })),
  payShare: (id: string, userId: string, key: string, proof?: string) =>
    need(api.post<ShareStarted>(`${g(id)}/shares/${encodeURIComponent(userId)}/pay`, { idempotencyKey: key, headers: proof ? { 'X-Step-Up': proof } : undefined })),
  confirmShare: (id: string, userId: string, key: string) => need(api.post<GroupView>(`${g(id)}/shares/${encodeURIComponent(userId)}/confirm`, { idempotencyKey: key })),
});
