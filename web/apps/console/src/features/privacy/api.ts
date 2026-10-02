import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * The console's privacy queue (S-105, api `PrivacyDeskController`):
 *   GET  /api/v1/console/privacy-requests?state=open|closed      the queue, soonest deadline first
 *   GET  /api/v1/console/privacy-requests/{id}                   one request: what was asked, the erasure's steps
 *   POST /api/v1/console/privacy-requests {contact, type, …}      a request made by email, phone or mail
 *   POST …/{id}/verify | /extend {reason} | /reject {decision, note?} | /start | /corrections {corrections} | /retry
 */
export const TYPES = ['access', 'correction', 'erasure'] as const;
export const DECISIONS = ['identity_not_verified', 'not_our_data', 'legal_exception', 'duplicate', 'frivolous'] as const;
export const EXTENSIONS = ['volume', 'consultation', 'conversion'] as const;

export const Item = z.object({
  id: z.string(), reference: z.string(), type: z.enum(TYPES), state: z.string(), subjectId: z.string(), subjectName: z.string(),
  subjectKind: z.string(), channel: z.string(), province: z.string(), law: z.string(), receivedAt: z.string(), dueAt: z.string(),
  overdue: z.boolean(), holdsOpen: z.number(),
});
export type Item = z.infer<typeof Item>;

const Kept = z.object({ category: z.string(), reason: z.string() });
export const Detail = z.object({
  id: z.string(), reference: z.string(), type: z.enum(TYPES), state: z.string(), subjectKind: z.string(), channel: z.string(), province: z.string(),
  law: z.object({ code: z.string(), name: z.string(), shortName: z.string(), authority: z.string(), responseDays: z.number(), businessDays: z.boolean(), extensionDays: z.number() }),
  receivedAt: z.string(), dueAt: z.string(), extendedTo: z.string().nullish(), extensionReason: z.string().nullish(), overdue: z.boolean(),
  verification: z.string().nullish(), verifiedAt: z.string().nullish(), scheduledFor: z.string().nullish(), completedAt: z.string().nullish(),
  decision: z.string().nullish(), decisionNote: z.string().nullish(),
  corrections: z.array(z.object({ field: z.string(), value: z.string() })), note: z.string().nullish(),
  steps: z.array(z.object({ module: z.string(), status: z.string(), attempts: z.number(), holds: z.array(Kept), retained: z.array(Kept) })),
  holdsOpen: z.number(),
});
export type Detail = z.infer<typeof Detail>;

const BASE = '/api/v1/console/privacy-requests';
const one = (id: string, action = '') => `${BASE}/${encodeURIComponent(id)}${action}`;

export const queueQuery = (state: 'open' | 'closed') => queryOptions({
  queryKey: ['console', 'privacy', state],
  queryFn: async () => (await http(`${BASE}?state=${state}`, {}, z.object({ items: z.array(Item) }))).items,
});
export const detailQuery = (id: string) => queryOptions({ queryKey: ['console', 'privacy', 'detail', id], queryFn: () => http(one(id), {}, Detail) });

export type Act =
  | { id: string; act: 'verify' | 'start' | 'retry' }
  | { id: string; act: 'extend'; reason: string }
  | { id: string; act: 'reject'; decision: string; note?: string }
  | { id: string; act: 'corrections'; corrections: { field: string; value: string }[] };

export function useAct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: Act) => {
      const body = a.act === 'extend' ? { reason: a.reason } : a.act === 'reject' ? { decision: a.decision, note: a.note } : a.act === 'corrections' ? { corrections: a.corrections } : undefined;
      return http(one(a.id, `/${a.act}`), { method: 'POST', body }, Detail);
    },
    onSuccess: d => { qc.setQueryData(detailQuery(d.id).queryKey, d); void qc.invalidateQueries({ queryKey: ['console', 'privacy'] }); },
  });
}

export function useRecord() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (r: { contact: string; type: string; corrections?: { field: string; value: string }[]; note?: string }) => http(BASE, { method: 'POST', body: r }, Detail),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'privacy'] }),
  });
}
