import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

const TrustFlag = z.object({ flagId: z.string(), rule: z.string(), source: z.string(), explanation: z.string(), categories: z.array(z.string()) });
/** One thing to vet (api `ListingVettingQueue.Item`). */
export const Item = z.object({
  id: z.string(), kind: z.enum(['product', 'service', 'dish']), merchantId: z.string(), businessName: z.string(), province: z.string().nullish(),
  name: z.string(), priceCents: z.number().nullish(), category: z.string().nullish(), medianCents: z.number().nullish(),
  deviationPct: z.number().nullish(), regulator: z.string().nullish(), flags: z.array(z.string()), revetReasons: z.array(z.string()),
  trustFlags: z.array(TrustFlag), state: z.string(), submittedAt: z.string().nullish(), decidedAt: z.string().nullish(),
  reasons: z.array(z.string()), note: z.string().nullish(),
});
export type Item = z.infer<typeof Item>;
/** `GET /api/v1/console/vetting` (S-92). */
export const Queue = z.object({ autoApproved: z.number(), flagged: z.number(), items: z.array(Item) });
export type Queue = z.infer<typeof Queue>;

export const REJECT_REASONS = ['prohibited', 'misleading', 'pricing', 'licence', 'images', 'other'] as const;
export type RejectReason = (typeof REJECT_REASONS)[number];

export const vettingQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'vetting', f.province ?? null, f.market ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (f.province) q.set('province', f.province);
    if (f.market) q.set('market', f.market);
    const s = q.toString();
    return http(`/api/v1/console/vetting${s ? `?${s}` : ''}`, {}, Queue);
  },
  placeholderData: keepPreviousData,
});

export function useDecideVetting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { item: Pick<Item, 'id' | 'kind'>; decision: 'approve' | 'reject'; reasons?: RejectReason[]; note?: string }) =>
      http(`/api/v1/console/vetting/${v.item.kind === 'dish' ? 'dishes' : 'listings'}/${encodeURIComponent(v.item.id)}/decision`,
        { method: 'POST', body: { decision: v.decision, reasons: v.reasons, note: v.note } }, Item),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'vetting'] }),
  });
}
