import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** `GET /api/v1/console/finance` (S-85; api `ViewFinance.Finance`). Money in cents. */
export const Finance = z.object({
  asOf: z.string(), timeZone: z.string(), escrowHeldCents: z.number(), escrowItems: z.number(), payoutsInFlightCents: z.number(), payoutsInFlightSellers: z.number(),
  nextPayoutArrival: z.string().nullish(), revenueWeekCents: z.number(),
  mix: z.object({ takeCents: z.number(), deliveryCents: z.number(), adjustmentsCents: z.number(), plusCents: z.number().nullish(), rewardsCents: z.number().nullish() }),
  tiers: z.array(z.object({ tier: z.enum(['registered', 'trusted', 'master']), sellers: z.number(), rateBps: z.number(), gmvShare: z.number().nullish() })),
  tax: z.object({ period: z.string(), platformFeeCents: z.number(), facilitatorCents: z.number(), nextFiling: z.string() }),
});
export type Finance = z.infer<typeof Finance>;

export const Day = z.object({
  day: z.string(), stripeCents: z.number(), ledgerCents: z.number(), feeCents: z.number(), items: z.number(), mismatches: z.number(),
  status: z.enum(['matched', 'mismatch', 'resolved']), computedAt: z.string(), resolvedNote: z.string().nullish(), resolvedBy: z.string().nullish(),
  resolvedAt: z.string().nullish(), varianceCents: z.number(),
});
export type Day = z.infer<typeof Day>;
export const Item = z.object({
  kind: z.enum(['charge', 'refund', 'dispute', 'payout', 'other']), stripeId: z.string().nullish(), stripeCents: z.number().nullish(),
  ledgerRefType: z.string().nullish(), ledgerRefId: z.string().nullish(), ledgerCents: z.number().nullish(),
  status: z.enum(['matched', 'missing_in_ledger', 'missing_at_stripe', 'amount_differs']),
});
export type Item = z.infer<typeof Item>;

const RECON = '/api/v1/console/payments/reconciliation';

export const financeQuery = queryOptions({ queryKey: ['console', 'finance'], queryFn: () => http('/api/v1/console/finance', {}, Finance) });
export const daysQuery = queryOptions({
  queryKey: ['console', 'finance', 'days'],
  queryFn: () => http(RECON, {}, z.object({ items: z.array(Day) })).then(r => r.items),
});
export const dayQuery = (day: string) => queryOptions({
  queryKey: ['console', 'finance', 'day', day],
  queryFn: () => http(`${RECON}/${day}`, {}, z.object({ day: Day, items: z.array(Item) })),
});

/** CSV downloads (the browser sends the session cookie; staff with the finance screen). */
export const exportUrl = (from: string, to: string) => `${RECON}/export?from=${from}&to=${to}`;
export const ledgerExportUrl = (from: string, to: string) => `${RECON}/ledger-export?from=${from}&to=${to}`;

function useFinanceMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'finance'] }) });
}
/** Reconcile a day now (`payouts`; audited). */
export const useRunDay = () => useFinanceMutation((day: string) => http(`${RECON}/run`, { method: 'POST', body: { day } }, Day));
/** Resolve a day that doesn't match, with a note (`payouts`; audited). */
export const useResolveDay = () => useFinanceMutation(({ day, note }: { day: string; note: string }) => http(`${RECON}/${day}/resolve`, { method: 'POST', body: { note } }, Day));

export const TaxReport = z.object({ period: z.string(), reported: z.number(), checked: z.number(), mismatched: z.number(), rows: z.number(), stillPending: z.number() });
/** S-21's Stripe Tax reconciliation of the quarter, now (`payouts`). */
export const useReconcileTax = () => useMutation({ mutationFn: (period: string) => http('/api/v1/console/payments/tax-reconciliations', { method: 'POST', body: { period } }, TaxReport) });
