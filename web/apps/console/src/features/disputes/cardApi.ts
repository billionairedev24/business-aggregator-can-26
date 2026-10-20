import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

/** Console › Disputes › Card disputes (S-156; server: payments' CardDisputes through the console module). */
const BASE = '/api/v1/console/card-disputes';

/** Stripe's evidence fields Northline sends (payments.api.CardDisputes). */
export const TEXT_FIELDS = ['product_description', 'service_date', 'cancellation_policy_disclosure', 'cancellation_rebuttal', 'refund_policy_disclosure', 'refund_refusal_explanation', 'access_activity_log', 'uncategorized_text'] as const;
export const FILE_FIELDS = ['receipt', 'service_documentation', 'customer_communication', 'cancellation_policy', 'refund_policy', 'uncategorized_file'] as const;

const Row = z.object({
  id: z.string(), caseNumber: z.string(), merchantId: z.string(), subject: z.string(), amountCents: z.number(), stripeDispute: z.string(),
  stripeStatus: z.string().nullish(), stripeReason: z.string().nullish(), dueBy: z.string().nullish(), respondBy: z.string().nullish(),
  openedAt: z.string(), decision: z.string().nullish(), submittedAt: z.string().nullish(), feeCents: z.number(),
});
export const CardItem = z.object({ row: Row, businessName: z.string(), province: z.string().nullish() });
export type CardItem = z.infer<typeof CardItem>;
export const CardDetail = z.object({
  item: CardItem,
  detail: z.object({
    merchantResponse: z.string().nullish(),
    caseEvidence: z.array(z.object({ id: z.string(), kind: z.string(), name: z.string(), contentType: z.string().nullish(), size: z.number(), by: z.string(), at: z.string(), file: z.boolean() })),
    fields: z.record(z.string(), z.string()), files: z.record(z.string(), z.string()),
    updatedBy: z.string().nullish(), updatedAt: z.string().nullish(), submittedBy: z.string().nullish(), lastError: z.string().nullish(),
  }),
});
export type CardDetail = z.infer<typeof CardDetail>;

export const cardDisputesQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'card-disputes', 'list', f.province ?? null, f.market ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (f.province) q.set('province', f.province);
    if (f.market) q.set('market', f.market);
    const s = q.toString();
    return http(`${BASE}${s ? `?${s}` : ''}`, {}, z.object({ items: z.array(CardItem) }));
  },
  placeholderData: keepPreviousData,
});
export const cardDisputeQuery = (id: string) => queryOptions({ queryKey: ['console', 'card-disputes', 'detail', id], queryFn: () => http(`${BASE}/${encodeURIComponent(id)}`, {}, CardDetail) });

function useCardMutation<V>(fn: (v: V) => Promise<CardDetail>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => void qc.invalidateQueries({ queryKey: ['console', 'card-disputes'] }) });
}
export const useSaveEvidence = () => useCardMutation((v: { id: string; fields: Record<string, string>; files: Record<string, string> }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/evidence`, { method: 'PUT', body: { fields: v.fields, files: v.files } }, CardDetail));
export const useSubmitEvidence = () => useCardMutation((id: string) => http(`${BASE}/${encodeURIComponent(id)}/evidence/submit`, { method: 'POST' }, CardDetail));
