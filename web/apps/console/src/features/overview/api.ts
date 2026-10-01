import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

const Backlog = z.object({ count: z.number(), oldest: z.string().nullish() });

/** `GET /api/v1/console/overview` (S-91; api `ViewOverview.Overview`). Money in cents, instants ISO-8601. */
export const Overview = z.object({
  asOf: z.string(),
  timeZone: z.string(),
  scope: z.object({ province: z.string().nullish(), market: z.string().nullish(), city: z.string().nullish() }),
  from: z.string(),
  headline: z.object({ gmvCents: z.number(), sellers: z.number(), verifications: z.number(), disputes: z.number() }),
  kpis: z.object({
    gmvCents: z.number(), previousGmvCents: z.number(), revenueCents: z.number(), orders: z.number(), bookings: z.number(),
    onTimeRatio: z.number().nullish(), disputeRate: z.number().nullish(), averageDeliveryFeeCents: z.number().nullish(),
  }),
  weeks: z.array(z.object({ start: z.string(), goodsCents: z.number(), servicesCents: z.number() })),
  health: z.array(z.object({ key: z.string(), value: z.number().nullish(), status: z.enum(['ok', 'degraded', 'unknown']) })),
  workQueue: z.object({
    verifications: Backlog, flaggedListings: Backlog, disputes: Backlog,
    stuckRuns: z.object({ count: z.number(), oldestOverdue: z.string().nullish() }),
    trustFlags: z.object({ count: z.number(), oldest: z.string().nullish(), offPlatformPayment: z.boolean() }),
    sellersBelowFloor: z.number(),
  }),
  live: z.object({
    couriersOnRuns: z.number(), couriersActive: z.number(), providersOnJobs: z.number(), escrowHeldCents: z.number(),
    pools: z.array(z.object({ market: z.string(), city: z.string(), label: z.string().nullish(), orders: z.number(), closesAt: z.string(), startsAt: z.string() })),
  }),
});
export type Overview = z.infer<typeof Overview>;

export interface OverviewFilter { province?: string; market?: string }

export const overviewQuery = ({ province, market }: OverviewFilter) => queryOptions({
  queryKey: ['console', 'overview', province ?? null, market ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (province) q.set('province', province);
    if (market) q.set('market', market);
    const qs = q.toString();
    return http(`/api/v1/console/overview${qs ? `?${qs}` : ''}`, {}, Overview);
  },
  // "live": refreshed every minute while the overview is open; the previous figures stay while a filter loads
  refetchInterval: 60_000,
  placeholderData: keepPreviousData,
});
