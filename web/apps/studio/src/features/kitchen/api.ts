import { queryOptions, useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** Kitchen portal API (food module): menus, items, modifier groups, combos, promos, setup, live orders. */
const m = (merchantId: string) => `/api/v1/merchants/${merchantId}`;
const items = <T extends z.ZodTypeAny>(item: T) => z.object({ items: z.array(item) }).transform(r => r.items);

// ── schemas ─────────────────────────────────────────────────────────────────

export const ALLERGENS = ['eggs', 'milk', 'peanuts', 'tree_nuts', 'sesame', 'soy', 'wheat', 'fish', 'shellfish', 'mustard', 'sulphites'] as const;
export type Allergen = (typeof ALLERGENS)[number];
export const DIETARY = ['gluten_free', 'vegan', 'vegetarian', 'halal', 'spicy', 'popular', 'vegan_option'] as const;
export const WINDOWS = ['always', 'lunch', 'after_5', 'weekends'] as const;
export type ItemWindow = (typeof WINDOWS)[number];

export const MenuSchedule = z.object({ mode: z.enum(['open_hours', 'window', 'quote']), days: z.array(z.number()).default([]), from: z.string().nullish(), to: z.string().nullish(), noticeHours: z.number().nullish() });
export type MenuSchedule = z.infer<typeof MenuSchedule>;
export const MenuStatus = z.enum(['draft', 'live', 'hidden']);
export const SectionRef = z.object({ id: z.string(), name: z.string(), sort: z.number(), itemCount: z.number().default(0) });
export const MenuSummary = z.object({ id: z.string(), name: z.string(), status: MenuStatus, schedule: MenuSchedule, sort: z.number(), publishedAt: z.string().nullish(), sections: z.array(SectionRef) });
export type MenuSummary = z.infer<typeof MenuSummary>;

export const Visibility = z.enum(['draft', 'needs_photo', 'price_check', 'awaiting_approval', 'live']);
/** S-67: price more than 40 % off comparable dishes; `confirmed` = the owner kept it (live). */
export const PriceCheck = z.object({ medianCents: z.number(), deviationPct: z.number(), confirmed: z.boolean() });
export type Visibility = z.infer<typeof Visibility>;
export const MenuItem = z.object({
  id: z.string(), menuId: z.string(), sectionId: z.string(), name: z.string(), description: z.string().nullish(), priceCents: z.number(),
  allergens: z.array(z.string()).nullable(), dietary: z.array(z.string()), prepAddMin: z.number(), dailyLimit: z.number().nullish(), soldToday: z.number(),
  soldOut: z.boolean(), availability: z.enum(WINDOWS), comboEligible: z.boolean(), modifierGroups: z.array(z.object({ id: z.string(), name: z.string() })),
  status: z.enum(['draft', 'published']), visibility: Visibility, hasPhoto: z.boolean(), updatedAt: z.string().nullish(),
  priceCheck: PriceCheck.nullish(),
  /** 2026-10-04: age-restricted dish (alcohol) */
  ageClass: z.string().nullish(),
});
export type MenuItem = z.infer<typeof MenuItem>;
export const MenuDetail = z.object({
  id: z.string(), name: z.string(), status: MenuStatus, schedule: MenuSchedule, publishedAt: z.string().nullish(), kitchenApproved: z.boolean(),
  sections: z.array(z.object({ id: z.string(), name: z.string(), sort: z.number(), items: z.array(MenuItem) })),
});
export type MenuDetail = z.infer<typeof MenuDetail>;

export const PickRule = z.enum(['exactly', 'at_least', 'up_to']);
export type PickRule = z.infer<typeof PickRule>;
export const ModifierOption = z.object({ id: z.string(), name: z.string(), priceDeltaCents: z.number(), isDefault: z.boolean(), soldOut: z.boolean() });
export type ModifierOption = z.infer<typeof ModifierOption>;
export const ModifierGroup = z.object({
  id: z.string(), name: z.string(), pickRule: PickRule, pickCount: z.number(), required: z.boolean(), minSelect: z.number(), maxSelect: z.number().nullish(),
  showForOptionIds: z.array(z.string()), options: z.array(ModifierOption), usedBy: z.number(),
});
export type ModifierGroup = z.infer<typeof ModifierGroup>;

export const ComboSlot = z.object({ label: z.string(), qty: z.number(), sectionId: z.string().nullish(), itemIds: z.array(z.string()).default([]) });
export type ComboSlot = z.infer<typeof ComboSlot>;
export const ComboWindow = z.object({ days: z.array(z.number()), from: z.string(), to: z.string() });
export const ComboStatus = z.enum(['draft', 'live', 'scheduled', 'paused']);
export type ComboStatus = z.infer<typeof ComboStatus>;
export const Combo = z.object({
  id: z.string(), name: z.string(), rule: z.string(), slots: z.array(ComboSlot), pricing: z.enum(['fixed', 'percent_off']), priceCents: z.number(),
  discountPct: z.number().nullish(), referenceCents: z.number(), savingCents: z.number(), schedule: ComboWindow.nullish(), status: ComboStatus, swapsAllowed: z.boolean(),
});
export type Combo = z.infer<typeof Combo>;
export const Promo = z.object({ promo: z.enum(['points_3x', 'first_order_5']), enabled: z.boolean() });
export type Promo = z.infer<typeof Promo>;

const Ranges = z.array(z.array(z.string()));
const Evidence = z.object({ reference: z.string().nullish(), status: z.string().nullish(), expiresAt: z.string().nullish() });
export const Setup = z.object({
  prep: z.object({ defaultPrepMin: z.number(), bumpMin: z.number(), shownMin: z.number(), maxOrdersPer15: z.number(), largeOrderCents: z.number(), largeOrderAddMin: z.number(), autoPauseLate: z.number().nullish() }),
  pausedUntil: z.string().nullish(),
  fulfilment: z.object({ courier: z.boolean(), pickup: z.boolean(), pickupFromMin: z.number(), pickupToMin: z.number(), mealKits: z.boolean(), scheduled: z.boolean(), scheduledDays: z.number(), groupOrders: z.boolean(), groupMax: z.number(), radiusKm: z.number().nullish(), areas: z.array(z.string()) }),
  hours: z.array(z.object({ weekday: z.number(), ranges: Ranges, note: z.string().nullish() })),
  holidays: z.array(z.object({ id: z.string(), day: z.string(), ranges: Ranges, note: z.string().nullish() })),
  menus: z.array(z.object({ menuId: z.string(), name: z.string(), status: MenuStatus, schedule: MenuSchedule })),
  foodSafety: z.object({ permit: Evidence, handlers: Evidence, inspection: Evidence }),
});
export type Setup = z.infer<typeof Setup>;

export const Stage = z.enum(['new', 'cooking', 'ready', 'handed_off', 'refused']);
export type Stage = z.infer<typeof Stage>;
export const Ticket = z.object({
  orderId: z.string(), ref: z.string().nullish(), customerName: z.string().nullish(), groupSize: z.number(), placedAt: z.string(), scheduledFor: z.string().nullish(),
  stage: Stage, lines: z.array(z.object({ qty: z.number(), title: z.string(), modifiers: z.array(z.string()) })), fulfilmentMode: z.enum(['delivery', 'pickup']),
  handoff: z.object({ party: z.enum(['courier', 'customer']), state: z.enum(['finding', 'assigned', 'arriving', 'waiting', 'none']), name: z.string().nullish(), eta: z.string().nullish() }),
  readyBy: z.string().nullish(),
  /** 2026-10-04: a pickup with age-restricted dishes — check photo ID for this age at the counter */
  idCheckAge: z.number().nullish(),
});
export type Ticket = z.infer<typeof Ticket>;
export const LiveBoard = z.object({
  items: z.array(Ticket), counts: z.object({ open: z.number(), fresh: z.number(), cooking: z.number(), ready: z.number() }),
  prep: z.object({ defaultPrepMin: z.number(), bumpMin: z.number(), shownMin: z.number() }), pausedUntil: z.string().nullish(),
  /** S-67: "Auto-pause if late orders ≥ threshold" — active while that many accepted orders are past their ready-by. */
  autoPause: z.object({ lateOrders: z.number(), threshold: z.number().nullish(), active: z.boolean() }).optional(),
});
export type LiveBoard = z.infer<typeof LiveBoard>;

// ── queries ─────────────────────────────────────────────────────────────────

export const kitchenKey = (merchantId: string, ...rest: string[]): QueryKey => ['merchant', merchantId, 'kitchen', ...rest];
/** New and moved orders arrive over the live stream (S-68); while it is down the kitchen display polls every 15 s. */
export const LIVE_POLL_MS = 15_000;

export const liveQuery = (merchantId: string) => queryOptions({ queryKey: kitchenKey(merchantId, 'live'), queryFn: () => http(`${m(merchantId)}/kitchen/live`, {}, LiveBoard), refetchInterval: LIVE_POLL_MS });
export const menusQuery = (merchantId: string) => queryOptions({ queryKey: ['merchant', merchantId, 'menus', '_list'], queryFn: () => http(`${m(merchantId)}/menus`, {}, items(MenuSummary)) });
export const menuQuery = (merchantId: string, menuId: string) => queryOptions({ queryKey: ['merchant', merchantId, 'menus', menuId], queryFn: () => http(`${m(merchantId)}/menus/${menuId}`, {}, MenuDetail), enabled: !!menuId });
export const groupsQuery = (merchantId: string) => queryOptions({ queryKey: ['merchant', merchantId, 'modifier-groups', '_full'], queryFn: () => http(`${m(merchantId)}/modifier-groups`, {}, items(ModifierGroup)) });
export const combosQuery = (merchantId: string) => queryOptions({ queryKey: kitchenKey(merchantId, 'combos'), queryFn: () => http(`${m(merchantId)}/combos`, {}, items(Combo)) });
export const promosQuery = (merchantId: string) => queryOptions({ queryKey: kitchenKey(merchantId, 'promos'), queryFn: () => http(`${m(merchantId)}/kitchen/promos`, {}, items(Promo)) });
export const setupQuery = (merchantId: string) => queryOptions({ queryKey: kitchenKey(merchantId, 'setup'), queryFn: () => http(`${m(merchantId)}/kitchen/setup`, {}, Setup) });
export const photoUrl = (merchantId: string, itemId: string, version?: string | null) => `${m(merchantId)}/menu-items/${itemId}/photo${version ? `?v=${encodeURIComponent(version)}` : ''}`;

// ── mutations ───────────────────────────────────────────────────────────────

export interface IdCheck { idChecked: boolean; recipientMatches: boolean; ofAge: boolean }
export const REFUSE_REASONS = ['no_id', 'underage', 'mismatch', 'nobody_of_age', 'intoxicated', 'other'] as const;
export type RefuseReason = (typeof REFUSE_REASONS)[number];

/** Age-restricted pickups (2026-10-04): hand over after the ID check, or refuse (the order is returned). */
export function useCounterCheck(merchantId: string) {
  const qc = useQueryClient();
  const key = liveQuery(merchantId).queryKey;
  return useMutation({
    mutationFn: (v: { orderId: string; idCheck: IdCheck } | { orderId: string; reason: RefuseReason }) => 'reason' in v
      ? http(`${m(merchantId)}/kitchen/live/${v.orderId}/refuse`, { method: 'POST', body: { reason: v.reason } }, LiveBoard)
      : http(`${m(merchantId)}/kitchen/live/${v.orderId}/handoff`, { method: 'POST', body: { idCheck: v.idCheck } }, LiveBoard),
    onSuccess: board => { qc.setQueryData(key, board); void qc.invalidateQueries({ queryKey: badges(merchantId) }); },
  });
}

const badges = (merchantId: string) => ['merchant', merchantId, 'nav-badges'];

/** Live-order actions answer with the fresh board; optimistic stage move, rolled back on error. */
export function useLiveAction(merchantId: string) {
  const qc = useQueryClient();
  const key = liveQuery(merchantId).queryKey;
  return useMutation({
    mutationFn: ({ orderId, action }: { orderId: string; action: 'accept' | 'ready' | 'handoff' }) => http(`${m(merchantId)}/kitchen/live/${orderId}/${action}`, { method: 'POST' }, LiveBoard),
    onMutate: async ({ orderId, action }) => {
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData<LiveBoard>(key);
      const next: Stage = action === 'accept' ? 'cooking' : action === 'ready' ? 'ready' : 'handed_off';
      if (prev) qc.setQueryData<LiveBoard>(key, { ...prev, items: prev.items.map(t => (t.orderId === orderId ? { ...t, stage: next } : t)).filter(t => t.stage !== 'handed_off') });
      return { prev };
    },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(key, ctx.prev); },
    onSuccess: board => qc.setQueryData(key, board),
    onSettled: () => { void qc.invalidateQueries({ queryKey: badges(merchantId) }); },
  });
}

/** Busy bump, reset, pause, resume. */
export function useKitchenToggle(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (what: 'bump' | 'reset' | 'pause' | 'resume') => http(`${m(merchantId)}/kitchen/${what === 'bump' || what === 'reset' ? 'prep-bump' : 'pause'}`, { method: what === 'bump' || what === 'pause' ? 'POST' : 'DELETE' }, LiveBoard),
    onSuccess: board => { qc.setQueryData(liveQuery(merchantId).queryKey, board); void qc.invalidateQueries({ queryKey: kitchenKey(merchantId, 'setup') }); },
  });
}

export interface ItemBody { menuId: string; sectionId: string; name: string; description: string; priceCents: number; prepAddMin: number; allergens: string[]; dietary: string[]; modifierGroupIds: string[]; availability: ItemWindow; dailyLimit: number | null; comboEligible: boolean; publish: boolean; ageClass?: 'alcohol' | null }

function useMenuMutation<V, R>(merchantId: string, fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'menus'] });
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'modifier-groups'] });
      void qc.invalidateQueries({ queryKey: badges(merchantId) });
    },
  });
}

export const useSaveItem = (merchantId: string) => useMenuMutation(merchantId, ({ id, body }: { id?: string; body: ItemBody }) =>
  http(id ? `${m(merchantId)}/menu-items/${id}` : `${m(merchantId)}/menu-items`, { method: id ? 'PUT' : 'POST', body }, MenuItem));
/** S-67: "Keep this price" for a dish the ±40 % check flagged. */
export const useConfirmPrice = (merchantId: string) => useMenuMutation(merchantId, (id: string) =>
  http(`${m(merchantId)}/menu-items/${id}/confirm-price`, { method: 'POST' }, MenuItem));
export const useDeleteItem = (merchantId: string) => useMenuMutation(merchantId, (id: string) => http(`${m(merchantId)}/menu-items/${id}`, { method: 'DELETE' }));
export const useUploadPhoto = (merchantId: string) => useMenuMutation(merchantId, ({ id, file }: { id: string; file: File }) => {
  const form = new FormData(); form.append('file', file);
  return http(`${m(merchantId)}/menu-items/${id}/photo`, { method: 'POST', body: form }, MenuItem);
});
export const useAddSection = (merchantId: string) => useMenuMutation(merchantId, ({ menuId, name }: { menuId: string; name: string }) => http(`${m(merchantId)}/menus/${menuId}/sections`, { method: 'POST', body: { name } }, SectionRef));
export const useRenameSection = (merchantId: string) => useMenuMutation(merchantId, ({ menuId, sectionId, name }: { menuId: string; sectionId: string; name: string }) => http(`${m(merchantId)}/menus/${menuId}/sections/${sectionId}`, { method: 'PATCH', body: { name } }, SectionRef));
export const useReorderSections = (merchantId: string) => useMenuMutation(merchantId, ({ menuId, sectionIds }: { menuId: string; sectionIds: string[] }) => http(`${m(merchantId)}/menus/${menuId}/sections/order`, { method: 'PUT', body: { sectionIds } }));
export const useCreateMenu = (merchantId: string) => useMenuMutation(merchantId, (name: string) => http(`${m(merchantId)}/menus`, { method: 'POST', body: { name } }, MenuSummary));
export const useMenuState = (merchantId: string) => useMenuMutation(merchantId, ({ menuId, action }: { menuId: string; action: 'publish' | 'hide' }) => http(`${m(merchantId)}/menus/${menuId}/${action}`, { method: 'POST' }, MenuSummary));
export const useImportCsv = (merchantId: string) => useMenuMutation(merchantId, ({ menuId, file }: { menuId: string; file: File }) => {
  const form = new FormData(); form.append('file', file);
  return http(`${m(merchantId)}/menus/${menuId}/import`, { method: 'POST', body: form }, z.object({ itemsCreated: z.number(), sectionsCreated: z.number() }));
});

// ── S-36 POS import ─────────────────────────────────────────────────────────

export const POS = ['square', 'clover', 'toast'] as const;
export type Pos = (typeof POS)[number];
export const PosConnection = z.object({
  provider: z.enum(POS), kind: z.enum(['oauth', 'restaurant_id']), available: z.boolean(), state: z.enum(['disconnected', 'connected', 'reconnect']),
  accountLabel: z.string().nullish(), connectedAt: z.string().nullish(), lastImportAt: z.string().nullish(),
});
export type PosConnection = z.infer<typeof PosConnection>;
const ItemChange = z.object({ externalId: z.string(), name: z.string(), section: z.string().nullish(), change: z.enum(['new', 'changed', 'unchanged', 'removed', 'problem']), fields: z.array(z.string()), priceCents: z.number().nullish(), previousPriceCents: z.number().nullish(), problem: z.string().nullish() });
export type ItemChange = z.infer<typeof ItemChange>;
export const PosPreview = z.object({
  id: z.string(), provider: z.enum(POS), menuId: z.string(), status: z.enum(['preview', 'applied', 'discarded']),
  diff: z.object({
    sections: z.array(z.object({ externalId: z.string(), name: z.string(), change: z.enum(['new', 'matched']) })),
    groups: z.array(z.object({ externalId: z.string(), name: z.string(), change: z.enum(['new', 'changed', 'unchanged', 'problem']), problem: z.string().nullish() })),
    items: z.array(ItemChange),
    counts: z.object({ newItems: z.number(), changedItems: z.number(), unchangedItems: z.number(), removedItems: z.number(), problems: z.number(), newSections: z.number(), newGroups: z.number(), changedGroups: z.number() }),
  }),
});
export type PosPreview = z.infer<typeof PosPreview>;
const PosApplied = z.object({ itemsCreated: z.number(), itemsUpdated: z.number(), itemsHidden: z.number(), sectionsCreated: z.number(), groupsCreated: z.number(), groupsUpdated: z.number(), skipped: z.number() });
const posKey = (merchantId: string) => ['merchant', merchantId, 'pos'] as const;
export const posConnectionsQuery = (merchantId: string) => queryOptions({ queryKey: posKey(merchantId), queryFn: () => http(`${m(merchantId)}/pos/connections`, {}, items(PosConnection)) });

/** The browser leaves for the POS's consent page (Square / Clover); tests replace it. */
export const goTo = { assign: (url: string) => window.location.assign(url) };

export function useConnectPos(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ pos, menuId, restaurantId }: { pos: Pos; menuId?: string; restaurantId?: string }) =>
      http(`${m(merchantId)}/pos/${pos}/connect`, { method: 'POST', body: { menuId, restaurantId } }, z.object({ authorizationUrl: z.string().nullish(), connection: PosConnection.nullish() })),
    onSuccess: r => { if (r.authorizationUrl) goTo.assign(r.authorizationUrl); else void qc.invalidateQueries({ queryKey: posKey(merchantId) }); },
  });
}
export function useDisconnectPos(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (pos: Pos) => http(`${m(merchantId)}/pos/${pos}/disconnect`, { method: 'POST', body: {} }, PosConnection),
    onSuccess: () => void qc.invalidateQueries({ queryKey: posKey(merchantId) }),
  });
}
export const usePosPreview = (merchantId: string) => useMutation({
  mutationFn: ({ menuId, pos }: { menuId: string; pos: Pos }) => http(`${m(merchantId)}/menus/${menuId}/pos-imports`, { method: 'POST', body: { provider: pos } }, PosPreview),
});
export const useApplyPosImport = (merchantId: string) => useMenuMutation(merchantId, (id: string) => http(`${m(merchantId)}/pos-imports/${id}/apply`, { method: 'POST', body: {} }, PosApplied));
export const useDiscardPosImport = (merchantId: string) => useMutation({
  mutationFn: (id: string) => http(`${m(merchantId)}/pos-imports/${id}/discard`, { method: 'POST', body: {} }, PosPreview),
});

/** Sold out today — optimistic in the open menu. */
export function useSoldOut(merchantId: string, menuId: string) {
  const qc = useQueryClient();
  const key = menuQuery(merchantId, menuId).queryKey;
  return useMutation({
    mutationFn: ({ id, soldOut }: { id: string; soldOut: boolean }) => http(`${m(merchantId)}/menu-items/${id}/sold-out`, { method: 'POST', body: { soldOut } }, MenuItem),
    onMutate: async ({ id, soldOut }) => {
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData<MenuDetail>(key);
      if (prev) qc.setQueryData<MenuDetail>(key, { ...prev, sections: prev.sections.map(s => ({ ...s, items: s.items.map(i => (i.id === id ? { ...i, soldOut } : i)) })) });
      return { prev };
    },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(key, ctx.prev); },
    onSettled: () => { void qc.invalidateQueries({ queryKey: key }); },
  });
}

export interface GroupBody { name: string; pickRule: PickRule; pickCount: number; required: boolean; showForOptionIds: string[]; options: { id?: string; name: string; priceDeltaCents: number; isDefault: boolean; soldOut: boolean }[] }
export const useSaveGroup = (merchantId: string) => useMenuMutation(merchantId, ({ id, body }: { id?: string; body: GroupBody }) =>
  http(id ? `${m(merchantId)}/modifier-groups/${id}` : `${m(merchantId)}/modifier-groups`, { method: id ? 'PUT' : 'POST', body }, ModifierGroup));
export const useAddOption = (merchantId: string) => useMenuMutation(merchantId, ({ id, name, priceDeltaCents }: { id: string; name: string; priceDeltaCents: number }) =>
  http(`${m(merchantId)}/modifier-groups/${id}/options`, { method: 'POST', body: { name, priceDeltaCents } }, ModifierGroup));
export const useDeleteGroup = (merchantId: string) => useMenuMutation(merchantId, (id: string) => http(`${m(merchantId)}/modifier-groups/${id}`, { method: 'DELETE' }));

export interface ComboBody { name: string; slots: { label: string; qty: number; sectionId: string | null; itemIds: string[] }[]; pricing: 'fixed' | 'percent_off'; priceCents: number | null; discountPct: number | null; schedule: { days: number[]; from: string; to: string } | null; status: ComboStatus; swapsAllowed: boolean }
function useComboMutation<V, R>(merchantId: string, fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => { void qc.invalidateQueries({ queryKey: kitchenKey(merchantId, 'combos') }); } });
}
export const useSaveCombo = (merchantId: string) => useComboMutation(merchantId, ({ id, body }: { id?: string; body: ComboBody }) =>
  http(id ? `${m(merchantId)}/combos/${id}` : `${m(merchantId)}/combos`, { method: id ? 'PUT' : 'POST', body }, Combo));
export const useDeleteCombo = (merchantId: string) => useComboMutation(merchantId, (id: string) => http(`${m(merchantId)}/combos/${id}`, { method: 'DELETE' }));

export function useSetPromo(merchantId: string) {
  const qc = useQueryClient();
  const key = promosQuery(merchantId).queryKey;
  return useMutation({
    mutationFn: ({ promo, enabled }: Promo) => http(`${m(merchantId)}/kitchen/promos/${promo}`, { method: 'PUT', body: { enabled } }, Promo),
    onMutate: async ({ promo, enabled }) => {
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData<Promo[]>(key);
      if (prev) qc.setQueryData<Promo[]>(key, prev.map(p => (p.promo === promo ? { ...p, enabled } : p)));
      return { prev };
    },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(key, ctx.prev); },
    onSettled: () => { void qc.invalidateQueries({ queryKey: key }); },
  });
}

export interface PrepBody { defaultPrepMin: number; maxOrdersPer15: number; largeOrderCents: number; autoPauseLate: number | null }
export interface FulfilmentBody { courier: boolean; pickup: boolean; mealKits: boolean; scheduled: boolean; scheduledDays: number; groupOrders: boolean; groupMax: number; radiusKm: number; areas: string[] }
export interface DayBody { weekday: number; ranges: string[][]; note: string | null }
function useSetupMutation<V>(merchantId: string, fn: (v: V) => Promise<Setup>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: setup => { qc.setQueryData(setupQuery(merchantId).queryKey, setup); void qc.invalidateQueries({ queryKey: kitchenKey(merchantId, 'live') }); } });
}
export const useSavePrep = (merchantId: string) => useSetupMutation(merchantId, (body: PrepBody) => http(`${m(merchantId)}/kitchen/prep`, { method: 'PUT', body }, Setup));
export const useSaveFulfilment = (merchantId: string) => useSetupMutation(merchantId, (body: FulfilmentBody) => http(`${m(merchantId)}/kitchen/fulfilment`, { method: 'PUT', body }, Setup));
export const useSaveHours = (merchantId: string) => useSetupMutation(merchantId, (days: DayBody[]) => http(`${m(merchantId)}/kitchen/hours`, { method: 'PUT', body: { days } }, Setup));
export const useAddHoliday = (merchantId: string) => useSetupMutation(merchantId, (body: { day: string; ranges: string[][]; note: string | null }) => http(`${m(merchantId)}/kitchen/holiday-hours`, { method: 'POST', body }, Setup));
export const useRemoveHoliday = (merchantId: string) => useSetupMutation(merchantId, (id: string) => http(`${m(merchantId)}/kitchen/holiday-hours/${id}`, { method: 'DELETE' }, Setup));
export function useSaveSchedule(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ menuId, schedule }: { menuId: string; schedule: MenuSchedule }) => http(`${m(merchantId)}/menus/${menuId}/schedule`, { method: 'PUT', body: schedule }, MenuSummary),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: kitchenKey(merchantId, 'setup') }); void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'menus'] }); },
  });
}
