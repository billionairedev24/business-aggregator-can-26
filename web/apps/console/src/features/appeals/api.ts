import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

/** Console › Trust & safety › Appeals (S-156; server: ca.northline.standing). */
const BASE = '/api/v1/console/appeals';

export const AppealView = z.object({
  id: z.string(), number: z.string(), subjectType: z.enum(['dispute', 'consequence']), subjectId: z.string(), subjectKind: z.string(),
  subjectLabel: z.string().nullish(), statement: z.string(), state: z.enum(['open', 'in_review', 'granted', 'denied']), filedAt: z.string(),
  decidedAt: z.string().nullish(), decisionReason: z.string().nullish(), compensationCents: z.number(),
  files: z.array(z.object({ id: z.string(), name: z.string(), contentType: z.string(), size: z.number() })),
});
export const QueueItem = z.object({
  appeal: AppealView, merchantId: z.string(), businessName: z.string(), province: z.string().nullish(),
  assigneeId: z.string().nullish(), assigneeName: z.string().nullish(),
});
export type QueueItem = z.infer<typeof QueueItem>;
const Consequence = z.object({
  id: z.string(), kind: z.string(), cause: z.string(), reason: z.string(), startedAt: z.string(), endsAt: z.string().nullish(), active: z.boolean(),
  liftedAt: z.string().nullish(), liftReason: z.string().nullish(),
});
const Dispute = z.object({ disputeId: z.string(), caseNumber: z.string(), subject: z.string(), decision: z.string(), refundCents: z.number(), decidedAt: z.string(), note: z.string().nullish() });
export const Detail = z.object({
  item: QueueItem, consequence: Consequence.nullish(), dispute: Dispute.nullish(),
  originalActor: z.string().nullish(), originalActorName: z.string().nullish(), decidedBy: z.string().nullish(), decidedByName: z.string().nullish(),
});
export type Detail = z.infer<typeof Detail>;

export const appealsQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'appeals', 'queue', f.province ?? null, f.market ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (f.province) q.set('province', f.province);
    if (f.market) q.set('market', f.market);
    const s = q.toString();
    return http(`${BASE}${s ? `?${s}` : ''}`, {}, z.object({ items: z.array(QueueItem) }));
  },
  placeholderData: keepPreviousData,
});
export const appealQuery = (id: string) => queryOptions({ queryKey: ['console', 'appeals', 'detail', id], queryFn: () => http(`${BASE}/${encodeURIComponent(id)}`, {}, Detail) });
export const appealFileUrl = (id: string, fileId: string) => `${BASE}/${encodeURIComponent(id)}/files/${encodeURIComponent(fileId)}`;

function useAppealMutation<V>(fn: (v: V) => Promise<Detail>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'appeals'] }) });
}
export const useAssignAppeal = () => useAppealMutation((id: string) => http(`${BASE}/${encodeURIComponent(id)}/assign`, { method: 'POST' }, Detail));
export const useDecideAppeal = () => useAppealMutation((v: { id: string; outcome: 'grant' | 'deny'; reason: string }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/decision`, { method: 'POST', body: { outcome: v.outcome, reason: v.reason } }, Detail));
