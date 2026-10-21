import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

const Field = z.object({ name: z.string(), kind: z.enum(['integer', 'number', 'words']), min: z.number(), max: z.number() });
/** One trust & safety rule (api `TrustRules.RuleView`). */
export const Rule = z.object({
  key: z.string(), value: z.record(z.string(), z.unknown()), defaults: z.record(z.string(), z.unknown()), fields: z.array(Field),
  updatedBy: z.string().nullish(), edited: z.string().nullish(),
});
export type Rule = z.infer<typeof Rule>;
export const Flag = z.object({
  id: z.string(), targetType: z.string(), targetId: z.string(), rule: z.string(), merchantId: z.string().nullish(), state: z.string(),
  source: z.string(), explanation: z.string(), categories: z.array(z.string()), createdAt: z.string(), decidedAt: z.string().nullish(),
  action: z.string().nullish(), businessName: z.string().nullish(), province: z.string().nullish(),
});
export type Flag = z.infer<typeof Flag>;
export const Impact = z.object({ rating: z.number(), days: z.number(), affected: z.number(), total: z.number() });
export type Impact = z.infer<typeof Impact>;

const qs = (f: PlaceFilter, extra: Record<string, string> = {}) => {
  const q = new URLSearchParams(extra);
  if (f.province) q.set('province', f.province);
  if (f.market) q.set('market', f.market);
  const s = q.toString();
  return s ? `?${s}` : '';
};
export const rulesQuery = queryOptions({
  queryKey: ['console', 'trust', 'rules'],
  queryFn: () => http('/api/v1/console/trust/rules', {}, z.object({ items: z.array(Rule) })),
});
export const flagsQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'trust', 'flags', f.province ?? null, f.market ?? null],
  queryFn: () => http(`/api/v1/console/trust/flags/queue${qs(f)}`, {}, z.object({ items: z.array(Flag) })),
  placeholderData: keepPreviousData,
});
export const impactOf = (rating: number, f: PlaceFilter) => http(`/api/v1/console/trust/rules/rating_floor/impact${qs(f, { rating: String(rating) })}`, {}, Impact);

function useTrustMutation<V>(fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'trust'] }) });
}
export const useSaveRule = () => useTrustMutation((v: { key: string; value: Record<string, unknown> }) =>
  http(`/api/v1/console/trust/rules/${v.key}`, { method: 'PUT', body: { value: v.value } }, Rule));
export const useFlagAction = () => useTrustMutation((v: { id: string; action: string; note?: string }) =>
  http(`/api/v1/console/trust/flags/${encodeURIComponent(v.id)}/action`, { method: 'POST', body: { action: v.action, note: v.note } }, Flag));
export const useFlagDecision = () => useTrustMutation((v: { id: string; decision: 'dismissed' | 'actioned'; note?: string }) =>
  http(`/api/v1/console/trust/flags/${encodeURIComponent(v.id)}/decision`, { method: 'POST', body: { decision: v.decision, note: v.note } }, Flag));

// ── S-158: the AI screening queue and the weekly anomaly scan ──────────────────────────────────────────────────────
export const AiFlag = Flag.extend({ evidence: z.record(z.string(), z.string()).default({}), decisionNote: z.string().nullish() });
export type AiFlag = z.infer<typeof AiFlag>;
export const aiFlagsQuery = (state: 'open' | 'all') => queryOptions({
  queryKey: ['console', 'trust', 'ai', state],
  queryFn: () => http(`/api/v1/console/trust/flags?source=ai&state=${state}&limit=200`, {}, z.object({ items: z.array(AiFlag) })),
  placeholderData: keepPreviousData,
});
export const FlagContent = z.object({ flagId: z.string(), targetType: z.string(), targetId: z.string(), title: z.string().nullish(), text: z.string(), context: z.string() });
export type FlagContent = z.infer<typeof FlagContent>;
/** What was flagged (the api audit-logs every opening). */
export const flagContentQuery = (id: string) => queryOptions({
  queryKey: ['console', 'trust', 'content', id],
  queryFn: () => http(`/api/v1/console/trust/flags/${encodeURIComponent(id)}/content`, {}, FlagContent),
  staleTime: Infinity,
});
export const Scan = z.object({
  id: z.string(), market: z.string(), weekStart: z.string(), merchants: z.number(), flagsRaised: z.number(), summary: z.string().nullish(),
  aiExplained: z.boolean(), ranAt: z.string(), flags: z.array(AiFlag),
});
export type Scan = z.infer<typeof Scan>;
export const scansQuery = queryOptions({
  queryKey: ['console', 'trust', 'scans'],
  queryFn: () => http('/api/v1/console/trust/anomaly-scans?limit=20', {}, z.object({ items: z.array(Scan) })),
});
