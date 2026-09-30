import { queryOptions, useQuery } from '@tanstack/react-query';
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
