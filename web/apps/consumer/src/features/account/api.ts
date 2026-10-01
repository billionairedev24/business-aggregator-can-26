import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';

/**
 * The values next to the account menu's items (design 06: "3 active", "12,480 pts", "Visa ··4471"…). Contract for the
 * account workstreams (S-58/S-59; docs/CONSUMER_WEB_PLAN.md § Account menu): `GET /api/v1/me/account-summary`. Every
 * field is optional — a missing one hides its value. Until the endpoint exists (404) the menu shows no values.
 */
export const AccountSummary = z.object({
  reliability: z.number().nullish(),
  points: z.object({ balance: z.number().int(), valueCents: z.number().int() }).nullish(),
  plus: z.boolean().nullish(),
  activeOrders: z.number().int().nullish(),
  favourites: z.number().int().nullish(),
  openCases: z.number().int().nullish(),
  paymentMethod: z.object({ brand: z.string(), last4: z.string() }).nullish(),
  addresses: z.object({ count: z.number().int(), members: z.number().int() }).nullish(),
  signIn: z.enum(['passkey', 'totp', 'sms']).nullish(),
  quietHours: z.object({ from: z.string(), to: z.string() }).nullish(),
  dietary: z.array(z.string()).nullish(),
  province: z.string().nullish(),
});
export type AccountSummary = z.infer<typeof AccountSummary>;

export const accountSummaryQuery = queryOptions({
  queryKey: ['me', 'account-summary'],
  queryFn: async (): Promise<AccountSummary | null> => {
    try { return await http('/api/v1/me/account-summary', {}, AccountSummary); } catch (e) { if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return null; throw e; }
  },
  staleTime: 60_000,
});

export const useAccountSummary = (enabled: boolean) => useQuery({ ...accountSummaryQuery, enabled });

// ── S-58: Orders & bookings, wallet, favourites ───────────────────────────────────────────────────────────────────

/**
 * One row of Orders & bookings (`GET /api/v1/me/activity`): the caller's orders, bookings and open quote requests,
 * active ones first. `status` and `action` are codes worded here; `href` is the consumer route the row opens.
 */
export const ActivityItem = z.object({
  id: z.string(),
  kind: z.enum(['order', 'food', 'booking', 'quote']),
  ref: z.string().nullish(),
  title: z.string(),
  with: z.array(z.string()),
  delivery: z.string().nullish(),
  shops: z.number().int(),
  items: z.number().int(),
  when: z.string(),
  whenEnd: z.string().nullish(),
  amountCents: z.number().int(),
  status: z.string(),
  tone: z.enum(['accent', 'neutral', 'accent-2']).catch('neutral'),
  active: z.boolean(),
  caseRef: z.object({ id: z.string(), number: z.string(), kind: z.string(), open: z.boolean() }).nullish(),
  action: z.enum(['track', 'details', 'view_quote', 'rebook', 'view_case']).catch('details'),
  href: z.string().nullish(),
});
export type ActivityItem = z.infer<typeof ActivityItem>;

export const activityQuery = queryOptions({
  queryKey: ['me', 'activity'],
  queryFn: async () => (await http('/api/v1/me/activity', {}, z.object({ items: z.array(ActivityItem) }))).items,
  staleTime: 30_000,
});

/** Wallet & points (`GET /api/v1/me/wallet`): 100 points = $1; `weekly` = points earned in each of the last 8 weeks. */
export const Wallet = z.object({
  points: z.object({ balance: z.number().int(), valueCents: z.number().int(), weekly: z.array(z.number().int()) }),
  plus: z.object({ plan: z.enum(['monthly', 'annual']), since: z.string().nullish(), renewsAt: z.string().nullish(), members: z.number().int() }).nullish(),
});
export type Wallet = z.infer<typeof Wallet>;

export const walletQuery = queryOptions({
  queryKey: ['me', 'wallet'],
  queryFn: () => http('/api/v1/me/wallet', {}, Wallet),
  staleTime: 60_000,
});

/** Favourite providers & shops (`GET /api/v1/me/favourites`), newest first. */
export const Favourite = z.object({
  merchantId: z.string(),
  name: z.string(),
  type: z.string(),
  tier: z.string(),
  slug: z.string().nullish(),
  categoryId: z.string().nullish(),
  visits: z.number().int(),
  lastAt: z.string().nullish(),
  openQuoteId: z.string().nullish(),
  addedAt: z.string(),
});
export type Favourite = z.infer<typeof Favourite>;

export const favouritesQuery = queryOptions({
  queryKey: ['me', 'favourites'],
  queryFn: async () => (await http('/api/v1/me/favourites', {}, z.object({ items: z.array(Favourite) }))).items,
  staleTime: 60_000,
});

/** Adds (`on = true`) or removes a favourite; the list and the menu's count follow. */
export function useFavourite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ merchantId, on }: { merchantId: string; on: boolean }) =>
      http(`/api/v1/me/favourites/${encodeURIComponent(merchantId)}`, { method: on ? 'PUT' : 'DELETE' }),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: favouritesQuery.queryKey }),
      qc.invalidateQueries({ queryKey: accountSummaryQuery.queryKey }),
    ]),
  });
}
