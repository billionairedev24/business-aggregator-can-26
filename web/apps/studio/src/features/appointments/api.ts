import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

export const JobState = z.enum(['requested', 'confirmed', 'en_route', 'on_site', 'completed', 'signed_off', 'disputed', 'cancelled']);
export type JobState = z.infer<typeof JobState>;

export const Job = z.object({
  id: z.string(), ref: z.string().nullish(), title: z.string(), startsAt: z.string(), endsAt: z.string(), state: JobState,
  memberUserId: z.string().nullish(), memberName: z.string().nullish(), customerName: z.string().nullish(), area: z.string().nullish(), priceCents: z.number().nullish(),
});
export type Job = z.infer<typeof Job>;

export const Approval = z.object({ id: z.string(), description: z.string(), amountCents: z.number(), state: z.enum(['pending', 'approved', 'declined']), requestedAt: z.string(), decidedAt: z.string().nullish() });
export type Approval = z.infer<typeof Approval>;
export const JobDetail = Job.extend({
  customer: z.object({ name: z.string(), reliability: z.number().nullish(), pastJobs: z.number() }).nullish(),
  addressLine: z.string().nullish(), access: z.string().nullish(), vehicle: z.string().nullish(), customerNote: z.string().nullish(), escrow: z.string().nullish(),
  /** The payments ledger's figures: what the customer paid and is held (incl. GST/HST), the tax, Northline's fee and the business's net. */
  escrowMoney: z.object({ state: z.string(), heldCents: z.number(), taxCents: z.number(), feeCents: z.number(), netCents: z.number() }).nullish(),
  timeline: z.array(z.object({ type: z.string(), at: z.string(), actorName: z.string().nullish(), note: z.string().nullish(), mediaId: z.string().nullish() })),
  approvals: z.array(Approval),
});
export type JobDetail = z.infer<typeof JobDetail>;

export const LineKind = z.enum(['labour', 'part', 'fee', 'travel', 'discount']);
export type LineKind = z.infer<typeof LineKind>;
export const Warranty = z.enum(['none', 'labour_90d', 'parts_labour_12m', 'manufacturer']);
export type Warranty = z.infer<typeof Warranty>;
export const DepositKind = z.enum(['none', 'parts_upfront', 'pct']);
export type DepositKind = z.infer<typeof DepositKind>;
export const Media = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), sizeBytes: z.number() });
export type Media = z.infer<typeof Media>;

export const Quote = z.object({
  id: z.string(), requestId: z.string(), ref: z.string(), version: z.number(), state: z.enum(['draft', 'sent', 'viewed', 'accepted', 'declined', 'expired', 'superseded']),
  lines: z.array(z.object({ kind: LineKind, description: z.string(), note: z.string().nullish(), qty: z.number(), unitCents: z.number(), amountCents: z.number(), taxable: z.boolean() })),
  labourCents: z.number(), partsCents: z.number(), feesCents: z.number(), discountCents: z.number(), subtotalCents: z.number(), taxBps: z.number(), taxCents: z.number(), totalCents: z.number(),
  depositKind: DepositKind, depositBps: z.number().nullish(), depositCents: z.number(), scope: z.string(), exclusions: z.string().nullish(), warranty: Warranty,
  proposedAt: z.string().nullish(), durationMin: z.number().nullish(), validHours: z.number(), validUntil: z.string().nullish(), sentAt: z.string().nullish(), attachments: z.array(Media),
});
export type Quote = z.infer<typeof Quote>;

export const QuoteRequest = z.object({
  id: z.string(), ref: z.string(), title: z.string(), customerName: z.string().nullish(), reliability: z.number().nullish(), area: z.string().nullish(), body: z.string().nullish(),
  preferredAt: z.string().nullish(), createdAt: z.string(), respondBy: z.string().nullish(), expiresAt: z.string().nullish(), quote: Quote.nullish(),
});
export type QuoteRequest = z.infer<typeof QuoteRequest>;

/** Request body of "Send quote" / "Revise" (validation-rules.md § Quote). */
export interface QuoteBody {
  lines: { kind: LineKind; description: string; qty: number; unitCents: number; taxable: boolean }[];
  scope: string; exclusions?: string; proposedAt?: string; durationMin?: number; validHours: number; warranty: Warranty; depositKind: DepositKind; depositBps?: number; attachments: string[];
}

const base = (m: string) => `/api/v1/merchants/${m}`;
const list = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item) }).transform(r => r.items as z.infer<T>[]);

export const jobsQuery = (m: string, from: string, to: string) => queryOptions({
  queryKey: ['merchant', m, 'jobs', from, to],
  queryFn: () => http(`${base(m)}/jobs?${new URLSearchParams({ from, to })}`, {}, list(Job)),
  placeholderData: prev => prev,
});
/** S-74: GET …/calendar-cells — "Open slot" (free bookable time) and "Held for quote" cells for days from `from`. */
export const CalendarCells = z.object({
  openSlots: z.array(z.object({ startsAt: z.string() })),
  quoteHolds: z.array(z.object({ quoteId: z.string(), requestId: z.string(), ref: z.string().nullish(), customerName: z.string(), startsAt: z.string(), durationMin: z.number() })),
});
export type CalendarCells = z.infer<typeof CalendarCells>;
export const calendarCellsQuery = (m: string, from: string, days: number) => queryOptions({
  queryKey: ['merchant', m, 'calendar-cells', from, days],
  queryFn: () => http(`${base(m)}/calendar-cells?${new URLSearchParams({ from, days: String(days) })}`, {}, CalendarCells),
  placeholderData: prev => prev,
});
export const jobQuery = (m: string, id: string) => queryOptions({ queryKey: ['merchant', m, 'job', id], queryFn: () => http(`${base(m)}/jobs/${id}`, {}, JobDetail) });
export const quoteRequestsQuery = (m: string) => queryOptions({ queryKey: ['merchant', m, 'quote-requests'], queryFn: () => http(`${base(m)}/quote-requests`, {}, list(QuoteRequest)) });
export const quoteQuery = (m: string, id: string) => queryOptions({ queryKey: ['merchant', m, 'quote', id], queryFn: () => http(`${base(m)}/quotes/${id}`, {}, Quote) });

export type Step = 'en-route' | 'on-site' | 'complete';
export function useAdvanceJob(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, step, body }: { id: string; step: Step; body?: Record<string, unknown> }) => http(`${base(m)}/jobs/${id}/${step}`, { method: 'POST', body: body ?? {} }, JobDetail),
    onSuccess: job => {
      qc.setQueryData(jobQuery(m, job.id).queryKey, job);
      void qc.invalidateQueries({ queryKey: ['merchant', m, 'jobs'] });
      void qc.invalidateQueries({ queryKey: ['merchant', m, 'dashboard'] });
    },
  });
}

export function useRequestApproval(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, description, amountCents }: { id: string; description: string; amountCents: number }) => http(`${base(m)}/jobs/${id}/approvals`, { method: 'POST', body: { description, amountCents } }, Approval),
    onSuccess: (_a, v) => qc.invalidateQueries({ queryKey: jobQuery(m, v.id).queryKey }),
  });
}

export function useUploadMedia(m: string) {
  return useMutation({
    mutationFn: (file: File) => { const fd = new FormData(); fd.append('file', file); return http(`${base(m)}/jobs/media`, { method: 'POST', body: fd }, Media); },
  });
}

function useQuoteInvalidate(m: string) {
  const qc = useQueryClient();
  return () => Promise.all([
    qc.invalidateQueries({ queryKey: quoteRequestsQuery(m).queryKey }),
    qc.invalidateQueries({ queryKey: ['merchant', m, 'dashboard'] }),
    qc.invalidateQueries({ queryKey: ['merchant', m, 'nav-badges'] }),
  ]);
}

export function useSendQuote(m: string) {
  const qc = useQueryClient();
  const inv = useQuoteInvalidate(m);
  return useMutation({
    mutationFn: ({ requestId, body }: { requestId: string; body: QuoteBody }) => http(`${base(m)}/quote-requests/${requestId}/quotes`, { method: 'POST', body }, Quote),
    onSuccess: (quote, v) => { qc.setQueryData(quoteRequestsQuery(m).queryKey, rs => rs?.map(r => (r.id === v.requestId ? { ...r, quote } : r))); void inv(); },
  });
}

export function useReviseQuote(m: string) {
  const qc = useQueryClient();
  const inv = useQuoteInvalidate(m);
  return useMutation({
    mutationFn: ({ quoteId, body }: { quoteId: string; body: QuoteBody }) => http(`${base(m)}/quotes/${quoteId}/revisions`, { method: 'POST', body }, Quote),
    onSuccess: quote => { qc.setQueryData(quoteRequestsQuery(m).queryKey, rs => rs?.map(r => (r.id === quote.requestId ? { ...r, quote } : r))); void inv(); },
  });
}

export function useDeclineRequest(m: string) {
  const qc = useQueryClient();
  const k = quoteRequestsQuery(m).queryKey;
  const inv = useQuoteInvalidate(m);
  return useMutation({
    mutationFn: (requestId: string) => http(`${base(m)}/quote-requests/${requestId}/decline`, { method: 'POST' }),
    onMutate: async id => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData(k); qc.setQueryData(k, rs => rs?.filter(r => r.id !== id)); return { prev }; },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => inv(),
  });
}
