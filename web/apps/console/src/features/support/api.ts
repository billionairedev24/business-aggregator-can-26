import { keepPreviousData, queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

/** The queue's chips in design order (api `SupportDesk.Filter`). */
export const FILTERS = ['all', 'urgent', 'unassigned', 'mine', 'sla_risk', 'providers', 'sellers', 'kitchens', 'customers'] as const;
export type TicketFilter = (typeof FILTERS)[number];

/** One helpdesk case (api `SupportDesk.Ticket`). */
export const Ticket = z.object({
  id: z.string(), code: z.string(), requesterType: z.string(), requesterName: z.string(), merchantId: z.string().nullish(),
  subject: z.string(), priority: z.enum(['urgent', 'priority', 'normal']), state: z.enum(['new', 'in_progress', 'waiting', 'resolved']),
  agentId: z.string().nullish(), agentName: z.string().nullish(), createdAt: z.string(), slaDueAt: z.string().nullish(),
  lang: z.string().nullish(), escalated: z.boolean(),
});
export type Ticket = z.infer<typeof Ticket>;
const Kpis = z.object({
  open: z.number(), urgent: z.number(), medianFirstReplyMinutes: z.number().nullish(), slaAtRisk: z.number(),
  resolvedWithoutEscalation: z.number().nullish(), csat: z.number().nullish(), frenchShare: z.number().nullish(),
});
export const Queue = z.object({ kpis: Kpis, counts: z.record(z.string(), z.number()), items: z.array(Ticket) });
export type Queue = z.infer<typeof Queue>;
export const RefundRequest = z.object({
  id: z.string(), amountCents: z.number(), note: z.string().nullish(), requestedBy: z.string(), requestedAt: z.string(),
  state: z.enum(['pending', 'approved', 'declined']), decidedBy: z.string().nullish(), decidedAt: z.string().nullish(), decisionNote: z.string().nullish(),
  requestedByName: z.string().nullish(),
});
export type RefundRequest = z.infer<typeof RefundRequest>;
export const Detail = z.object({
  ticket: Ticket,
  context: z.record(z.string(), z.unknown()),
  refLabel: z.string().nullish(),
  notes: z.array(z.object({
    by: z.string(), name: z.string().nullish(), body: z.string(), at: z.string(),
    // mobile gaps part 1: the photos a customer attached to a report (app or web), opened through the ticket
    attachments: z.array(z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), size: z.number() })).optional(),
  })),
  refundRequests: z.array(RefundRequest),
});
export type Detail = z.infer<typeof Detail>;
const I18n = z.record(z.string(), z.string());
export const Macro = z.object({ id: z.string(), key: z.string(), title: I18n, body: I18n, updatedAt: z.string().nullish() });
export type Macro = z.infer<typeof Macro>;

const BASE = '/api/v1/console/support';
const qs = (f: PlaceFilter, extra: Record<string, string> = {}) => {
  const q = new URLSearchParams(extra);
  if (f.province) q.set('province', f.province);
  if (f.market) q.set('market', f.market);
  const s = q.toString();
  return s ? `?${s}` : '';
};

export const ticketsQuery = (f: PlaceFilter, filter: TicketFilter) => queryOptions({
  queryKey: ['console', 'support', 'tickets', filter, f.province ?? null, f.market ?? null],
  queryFn: () => http(`${BASE}/tickets${qs(f, filter === 'all' ? {} : { filter })}`, {}, Queue),
  placeholderData: keepPreviousData,
});
export const ticketQuery = (id: string) => queryOptions({
  queryKey: ['console', 'support', 'ticket', id],
  queryFn: () => http(`${BASE}/tickets/${encodeURIComponent(id)}`, {}, Detail),
});
export const macrosQuery = queryOptions({
  queryKey: ['console', 'support', 'macros'],
  queryFn: () => http(`${BASE}/macros`, {}, z.object({ items: z.array(Macro) })),
  staleTime: 60_000,
});
export const useMacros = () => useQuery(macrosQuery);

function useDeskMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'support'] }) });
}
const post = (path: string, body: unknown) => http(`${BASE}${path}`, { method: 'POST', body }, Detail);
const ticketPath = (id: string, action: string) => `/tickets/${encodeURIComponent(id)}/${action}`;

export const useReply = () => useDeskMutation((v: { id: string; body: string; resolve: boolean; macroKey?: string }) =>
  post(ticketPath(v.id, 'reply'), { body: v.body, resolve: v.resolve, macroKey: v.macroKey }));
export const useTake = () => useDeskMutation((id: string) => post(ticketPath(id, 'take'), {}));
export const useEscalate = () => useDeskMutation((v: { id: string; note?: string }) => post(ticketPath(v.id, 'escalate'), { note: v.note }));
export const useRequestRefund = () => useDeskMutation((v: { id: string; amountCents: number; note?: string }) =>
  post(ticketPath(v.id, 'refund-requests'), { amountCents: v.amountCents, note: v.note }));
export const useDecideRefund = () => useDeskMutation((v: { requestId: string; decision: 'approve' | 'decline'; note?: string }) =>
  post(`/refund-requests/${encodeURIComponent(v.requestId)}/decision`, { decision: v.decision, note: v.note }));
export const useSaveMacro = () => useDeskMutation((v: { id?: string; key: string; title: Record<string, string>; body: Record<string, string> }) =>
  http(`${BASE}/macros${v.id ? `/${encodeURIComponent(v.id)}` : ''}`, { method: v.id ? 'PUT' : 'POST', body: { key: v.key, title: v.title, body: v.body } }, Macro));
export const useDeleteMacro = () => useDeskMutation((id: string) =>
  http(`${BASE}/macros/${encodeURIComponent(id)}`, { method: 'DELETE' }, z.unknown()));

/** A file on a case message, served to support only (no-store): `GET /console/support/tickets/{id}/attachments/{file}`. */
export const attachmentUrl = (ticketId: string, fileId: string) =>
  `/api/v1/console/support/tickets/${encodeURIComponent(ticketId)}/attachments/${encodeURIComponent(fileId)}`;
