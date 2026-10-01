import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** `GET /api/v1/console/reports?province=` (S-95; api `ViewReports.Report`). Counts only; withheld small counts are null. */
export const Report = z.object({
  asOf: z.string(), province: z.string().nullish(), from: z.string(),
  weeks: z.array(z.object({ week: z.string(), customers: z.number().nullish(), previous: z.number().nullish() })),
  funnel: z.array(z.object({ step: z.enum(['app_opens', 'browsed', 'cart', 'checkout', 'paid']), count: z.number().nullish(), recorded: z.boolean() })),
  cohorts: z.array(z.object({ month: z.string(), customers: z.number().nullish(), m1: z.number().nullish(), m2: z.number().nullish(), m3: z.number().nullish() })),
  topCategories: z.array(z.object({ categoryId: z.string(), names: z.record(z.string(), z.string()), salesCents: z.number() })),
  waitlist: z.array(z.object({ province: z.string(), people: z.number().nullish() })),
});
export type Report = z.infer<typeof Report>;

export const reportQuery = (province?: string) => queryOptions({
  queryKey: ['console', 'reports', province ?? null],
  queryFn: () => http(`/api/v1/console/reports${province ? `?province=${encodeURIComponent(province)}` : ''}`, {}, Report),
  placeholderData: keepPreviousData,
});
