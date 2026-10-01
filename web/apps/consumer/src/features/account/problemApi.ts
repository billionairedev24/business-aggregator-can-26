import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import { accountSummaryQuery, ActivityItem, activityQuery } from './api';

/** "Something's wrong" and Help & cases (S-60), all `/api/v1/me`. */
export const ProblemKind = z.enum(['order', 'food', 'booking']);
export type ProblemKind = z.infer<typeof ProblemKind>;
const Card = z.object({ brand: z.string(), last4: z.string() });

export const ProblemItem = z.object({
  ref: z.string(), title: z.string(), qty: z.number().int(), amountCents: z.number().int(), taxCents: z.number().int(),
  merchantId: z.string(), merchantName: z.string(), status: z.enum(['open', 'reported', 'closed', 'not_yet', 'not_paid']), reportBy: z.string().nullish(),
});
export type ProblemItem = z.infer<typeof ProblemItem>;
export const ProblemContext = z.object({
  kind: ProblemKind, id: z.string(), ref: z.string().nullish(), title: z.string(), date: z.string(), items: z.array(ProblemItem),
  reasons: z.array(z.string()), status: z.enum(['open', 'reported', 'closed', 'not_yet', 'not_paid']), reportBy: z.string().nullish(), card: Card.nullish(),
});
export type ProblemContext = z.infer<typeof ProblemContext>;
export const problemQuery = (kind: string, id: string) => queryOptions({
  queryKey: ['me', 'problem', kind, id],
  queryFn: () => http(`/api/v1/me/problems/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`, {}, ProblemContext),
});

export const Reported = z.object({
  caseId: z.string(), caseCode: z.string(), submittedAt: z.string(), totalCents: z.number().int(), card: Card.nullish(),
  refunds: z.array(z.object({ id: z.string(), number: z.string(), amountCents: z.number().int(), taxCents: z.number().int(), merchantName: z.string(), respondBy: z.string().nullish() })),
});
export type Reported = z.infer<typeof Reported>;
export interface ReportInput {
  kind: ProblemKind; id: string; items: string[]; reason: string; note?: string; attachmentIds: string[];
  triageCategory?: string; triageSummary?: string;
}
export function useReport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (r: ReportInput) => http('/api/v1/me/problems', { method: 'POST', body: r }, Reported),
    onSuccess: () => Promise.all([activityQuery.queryKey, accountSummaryQuery.queryKey, casesQuery.queryKey].map(k => qc.invalidateQueries({ queryKey: k }))),
  });
}

export const Upload = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), byteSize: z.number() });
export type Upload = z.infer<typeof Upload>;
export const uploadPhoto = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return http('/api/v1/me/case-uploads', { method: 'POST', body: form }, Upload);
};

/**
 * S-132's triage (`POST /api/v1/me/help/triage`): a suggested category for what the person wrote. Optional — any
 * failure (AI off, budget spent, an older api) means no suggestion.
 */
export const Triage = z.object({ category: z.string(), route: z.string().nullish(), urgent: z.boolean().nullish(), summary: z.string().nullish() });
export type Triage = z.infer<typeof Triage>;
export async function triage(text: string, kind: ProblemKind): Promise<Triage | null> {
  try {
    return await http('/api/v1/me/help/triage', { method: 'POST', body: { text, refType: kind === 'booking' ? 'booking' : 'order' } }, Triage);
  } catch {
    return null;
  }
}
/** S-132 categories → this screen's reasons. */
export const CATEGORY_REASON: Record<string, string> = {
  missing_item: 'missing', not_delivered: 'missing', wrong_item: 'wrong_item', damaged: 'damaged', not_as_described: 'poor_quality',
  late: 'late', service_not_done: 'not_done', service_quality: 'poor_quality', no_show: 'no_show', billing: 'billing',
};

// ── Help & cases ────────────────────────────────────────────────────────────────────────────────────────────────────
export const CaseRow = z.object({
  id: z.string(), number: z.string(), kind: z.string(), state: z.string(), open: z.boolean(), what: z.string(), amountCents: z.number().int(),
  taxCents: z.number().int(), merchantName: z.string(), openedAt: z.string(), respondBy: z.string().nullish(), outcome: z.string().nullish(),
  settledCents: z.number().int().nullish(), subject: ActivityItem.nullish(),
});
export type CaseRow = z.infer<typeof CaseRow>;
export const casesQuery = queryOptions({ queryKey: ['me', 'cases'], queryFn: async () => (await http('/api/v1/me/cases', {}, z.object({ items: z.array(CaseRow) }))).items });

const Attachment = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), byteSize: z.number() });
export const CaseDetail = z.object({
  row: CaseRow,
  steps: z.array(z.object({ key: z.enum(['submitted', 'seller', 'northline', 'refund']), state: z.enum(['done', 'current', 'todo', 'skipped', 'denied']), at: z.string().nullish() })),
  card: Card.nullish(),
  thread: z.object({ id: z.string(), code: z.string(), state: z.string(), notes: z.array(z.object({ at: z.string(), by: z.enum(['you', 'northline']), body: z.string(), attachments: z.array(Attachment) })) }).nullish(),
});
export type CaseDetail = z.infer<typeof CaseDetail>;
export const caseQuery = (id: string) => queryOptions({ queryKey: ['me', 'case', id], queryFn: () => http(`/api/v1/me/cases/${encodeURIComponent(id)}`, {}, CaseDetail) });
export function useAddNote(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (n: { body: string; attachmentIds: string[] }) => http(`/api/v1/me/cases/${encodeURIComponent(id)}/notes`, { method: 'POST', body: n }, CaseDetail),
    onSuccess: data => qc.setQueryData(caseQuery(id).queryKey, data),
  });
}
