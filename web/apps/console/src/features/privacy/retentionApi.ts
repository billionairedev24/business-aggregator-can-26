import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * The retention report (S-107, api `RetentionController`):
 *   GET  /api/v1/console/retention              every category of the Privacy Policy's schedule and what its job did
 *   GET  /api/v1/console/retention/export       the same as CSV
 *   POST /api/v1/console/retention/runs {dryRun, category?}   run or dry-run now (the `privacy` grant)
 */
export const RunView = z.object({
  category: z.string(), dryRun: z.boolean(), trigger: z.string(), startedAt: z.string(), finishedAt: z.string(),
  outcome: z.string(), affected: z.number(), held: z.number(), remaining: z.number(),
});
export type RunView = z.infer<typeof RunView>;

export const Category = z.object({
  code: z.string(), module: z.string(), name: z.string(), clause: z.string().nullish(), policy: z.string().nullish(),
  period: z.string().nullish(), afterDisputeClosed: z.string().nullish(), lawMinimum: z.boolean(), starts: z.string(), basis: z.string(),
  action: z.enum(['delete', 'pseudonymise', 'aggregate']), enforcement: z.enum(['job', 'pipeline', 'infrastructure', 'none', 'blocked']),
  holds: z.array(z.string()), note: z.string().nullish(), lastRun: RunView.nullish(), lastSuccessAt: z.string().nullish(),
  rowsAffected: z.number(), held: z.number(), nextDueAt: z.string().nullish(), overdue: z.boolean(),
});
export type Category = z.infer<typeof Category>;

export const Report = z.object({
  generatedAt: z.string(), nextRunAt: z.string().nullish(), dryRunOnly: z.boolean(), categories: z.array(Category),
  operational: z.array(z.object({ code: z.string(), name: z.string(), period: z.string(), where: z.string(), setting: z.string() })),
  laws: z.array(z.object({ code: z.string(), name: z.string(), decisionRetentionDays: z.number() })),
});
export type Report = z.infer<typeof Report>;

const BASE = '/api/v1/console/retention';
export const EXPORT_URL = `${BASE}/export`;

export const reportQuery = () => queryOptions({ queryKey: ['console', 'retention'], queryFn: () => http(BASE, {}, Report) });

export function useRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (r: { dryRun: boolean; category?: string }) =>
      http(`${BASE}/runs`, { method: 'POST', body: r }, z.object({ items: z.array(RunView) })).then(x => x.items),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'retention'] }),
  });
}

/** `P2Y` → {n: 2, unit: 'y'}, `PT24H` → {n: 24, unit: 'h'}; the screen words it in the viewer's language. */
export function periodParts(iso: string | null | undefined): { n: number; unit: 'y' | 'm' | 'd' | 'h' | 'min' } | undefined {
  const time = iso ? /^PT(\d+)([HM])$/.exec(iso) : null;
  if (time) return { n: Number(time[1]), unit: time[2] === 'H' ? 'h' : 'min' };
  const m = iso ? /^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?$/.exec(iso) : null;
  if (!m) return undefined;
  if (m[1]) return { n: Number(m[1]), unit: 'y' };
  if (m[2]) return { n: Number(m[2]), unit: 'm' };
  return { n: Number(m[3] ?? 0), unit: 'd' };
}
