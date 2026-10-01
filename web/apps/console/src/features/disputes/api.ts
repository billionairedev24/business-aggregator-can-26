import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

export const OUTCOMES = ['full_refund', 'partial', 'release', 'goodwill_credit'] as const;
export type Outcome = (typeof OUTCOMES)[number];

const AgentDecision = z.object({
  id: z.string(), outcome: z.enum(OUTCOMES), refundCents: z.number(), note: z.string().nullish(), decidedBy: z.string(), decidedAt: z.string(),
  state: z.string(), cosignedBy: z.string().nullish(), cosignedAt: z.string().nullish(), cosignNote: z.string().nullish(),
});
export type AgentDecision = z.infer<typeof AgentDecision>;
const CaseRow = z.object({
  kind: z.enum(['dispute', 'refund']), id: z.string(), caseNumber: z.string(), merchantId: z.string(), subject: z.string(), amountCents: z.number(),
  customerName: z.string().nullish(), customerStatement: z.string().nullish(), sellerStatement: z.string().nullish(), state: z.string(),
  openedAt: z.string(), pending: AgentDecision.nullish(), decision: AgentDecision.nullish(),
});
/** One case with its business (api `DisputeDesk.Item`). */
export const Item = z.object({ row: CaseRow, businessName: z.string(), province: z.string().nullish() });
export type Item = z.infer<typeof Item>;
/** `GET /api/v1/console/disputes` (S-80). */
export const Queue = z.object({ summary: z.object({ forAgent: z.number(), inSellerWindow: z.number(), closedThisWeek: z.number() }), items: z.array(Item) });
export const Evidence = z.object({ id: z.string(), kind: z.string(), name: z.string(), contentType: z.string().nullish(), size: z.number(), by: z.string(), at: z.string(), file: z.boolean() });
export const Detail = z.object({
  item: Item,
  detail: z.object({ evidence: z.array(Evidence), customerPriorDisputes: z.number(), sellerPriorDisputes: z.number(), sellerPriorWon: z.number(), offerCents: z.number().nullish(), offerState: z.string().nullish() }),
  sellerQuality: z.number().nullish(),
});
export type Detail = z.infer<typeof Detail>;

const BASE = '/api/v1/console/disputes';
export const disputesQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'disputes', 'queue', f.province ?? null, f.market ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (f.province) q.set('province', f.province);
    if (f.market) q.set('market', f.market);
    const s = q.toString();
    return http(`${BASE}${s ? `?${s}` : ''}`, {}, Queue);
  },
  placeholderData: keepPreviousData,
});
export const caseQuery = (kind: string, id: string) => queryOptions({
  queryKey: ['console', 'disputes', 'case', kind, id],
  queryFn: () => http(`${BASE}/${kind}/${encodeURIComponent(id)}`, {}, Detail),
});
export const evidenceUrl = (disputeId: string, evidenceId: string) => `${BASE}/dispute/${encodeURIComponent(disputeId)}/evidence/${encodeURIComponent(evidenceId)}`;

function useCaseMutation<V>(fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'disputes'] }) });
}
export const useDecideCase = () => useCaseMutation((v: { kind: string; id: string; outcome: Outcome; refundCents?: number; note?: string }) =>
  http(`${BASE}/${v.kind}/${encodeURIComponent(v.id)}/decision`, { method: 'POST', body: { outcome: v.outcome, refundCents: v.refundCents, note: v.note } }, Item));
export const useCosign = () => useCaseMutation((v: { decisionId: string; decision: 'approve' | 'decline'; note?: string }) =>
  http(`${BASE}/decisions/${encodeURIComponent(v.decisionId)}/cosign`, { method: 'POST', body: { decision: v.decision, note: v.note } }, Item));
