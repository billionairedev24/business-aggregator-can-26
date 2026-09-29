import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** GET /api/v1/merchants/{id}/dashboard — composed server-side by the studio module (see Dashboard.java). */
export const TodayJob = z.object({
  id: z.string(), startsAt: z.string(), title: z.string(), customerName: z.string().nullish(), area: z.string().nullish(),
  access: z.string().nullish(), escrowHeldCents: z.number().nullish(), state: z.string(), memberName: z.string().nullish(), mine: z.boolean(),
});
export type TodayJob = z.infer<typeof TodayJob>;
export const RunOrder = z.object({
  orderId: z.string(), ref: z.string().nullish(), runLabel: z.string().nullish(), customerName: z.string().nullish(), area: z.string().nullish(),
  items: z.array(z.object({ title: z.string(), qty: z.number() })), packed: z.boolean(),
});
export type RunOrder = z.infer<typeof RunOrder>;
export const Dashboard = z.object({
  today: z.string(),
  jobsToday: z.array(TodayJob),
  run: z.array(RunOrder),
  counts: z.object({
    visitsToday: z.number(), quoteRequestsOpen: z.number(), quoteRespondBy: z.string().nullish(), toPack: z.number(),
    runCutoff: z.string().nullish(), runLabel: z.string().nullish(), jobsThisMonth: z.number(), ordersThisMonth: z.number(), itemsThisMonth: z.number(),
  }),
  earnings: z.object({
    netThisMonthCents: z.number(), netLastMonthCents: z.number(), releasingCents: z.number().nullish(), releasingAt: z.string().nullish(),
    weeks: z.array(z.object({ start: z.string(), servicesCents: z.number(), partsCents: z.number() })),
  }),
  reputation: z.object({
    rating: z.number().nullish(), reviews: z.number(), qualityScore: z.number().nullish(), quality: z.record(z.string(), z.number()), refundRateBps: z.number().nullish(),
  }),
  cases: z.array(z.object({ kind: z.string(), customerName: z.string().nullish(), subject: z.string().nullish() })),
  lowStock: z.array(z.object({ name: z.string(), stock: z.number() })),
  compliance: z.array(z.object({ checkType: z.string(), registry: z.string().nullish(), status: z.string(), expiresAt: z.string().nullish(), pausesAt: z.string().nullish() })),
  coaching: z.object({ jobs: z.number(), withoutPhotos: z.number(), nextTierReview: z.string() }),
});
export type Dashboard = z.infer<typeof Dashboard>;

export const dashboardQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'dashboard'],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/dashboard`, {}, Dashboard),
  staleTime: 30_000,
});
