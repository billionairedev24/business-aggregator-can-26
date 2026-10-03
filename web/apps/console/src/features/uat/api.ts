import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * The console's UAT screen (S-121, api `UatConsoleController`):
 *   GET  /api/v1/console/uat/feedback?state=&blocking=&persona=&app=    the queue; …/export is the CSV
 *   GET  /api/v1/console/uat/feedback/{id}                              one item, its duplicates and history
 *   POST …/{id}/moves {to, blocking?, duplicateOf?, note?} | …/{id}/owner {ownerId} | …/{id}/tracker {url}
 *   GET  /api/v1/console/uat/owners | /participants | /scripts | /go-no-go (…/export)
 *   POST /api/v1/console/uat/participants {persona, label, contact} (businesses: S-120's cohort) | …/{id}/deactivate | …/{id}/signoffs
 */
export const STATES = ['new', 'triaged', 'accepted', 'fixed', 'verified', 'closed', 'wont_fix', 'duplicate'] as const;
export type State = (typeof STATES)[number];
export const PERSONAS = ['provider', 'seller', 'kitchen', 'customer', 'courier', 'staff'] as const;
export type Persona = (typeof PERSONAS)[number];
export const OUTCOMES = ['signed_off', 'with_comments', 'blocked'] as const;

export const Item = z.object({
  id: z.string(), reference: z.string(), state: z.enum(STATES), blocking: z.boolean().nullish(), category: z.string(), severity: z.string(),
  persona: z.enum(PERSONAS), participant: z.string(), app: z.string(), summary: z.string(), route: z.string(),
  ownerId: z.string().nullish(), ownerName: z.string().nullish(), trackerUrl: z.string().nullish(), duplicateOf: z.string().nullish(),
  duplicates: z.number(), screenshot: z.boolean(), createdAt: z.string(), updatedAt: z.string(),
});
export type Item = z.infer<typeof Item>;

export const Detail = z.object({
  item: Item, body: z.string(), appVersion: z.string(), locale: z.string(), platform: z.string(), merchantId: z.string().nullish(),
  next: z.array(z.enum(STATES)), duplicates: z.array(Item), duplicateOfItem: Item.nullish(),
  history: z.array(z.object({ from: z.string().nullish(), to: z.string(), blocking: z.boolean().nullish(), actorId: z.string(), actorName: z.string().nullish(), note: z.string().nullish(), at: z.string() })),
});
export type Detail = z.infer<typeof Detail>;

const Signoff = z.object({
  script: z.string(), scriptTitle: z.string(), scriptVersion: z.string(), outcome: z.enum(['signed_off', 'with_comments', 'blocked', 'pending']),
  comments: z.string().nullish(), blockingRefs: z.array(z.string()), recordedByName: z.string().nullish(), recordedAt: z.string().nullish(), history: z.number(),
});
export const Participant = z.object({
  id: z.string(), persona: z.enum(PERSONAS), label: z.string(), who: z.enum(['user', 'business']), merchantId: z.string().nullish(),
  active: z.boolean(), since: z.string(), feedbackCount: z.number(), signoffs: z.array(Signoff),
});
export type Participant = z.infer<typeof Participant>;

export const Script = z.object({ code: z.string(), persona: z.enum(PERSONAS), title: z.string(), version: z.string(), docPath: z.string(), formPath: z.string() });

export const Report = z.object({
  generatedAt: z.string(), verdict: z.enum(['go', 'no_go']),
  reasons: z.array(z.object({ code: z.string(), count: z.number(), persona: z.string().nullish(), text: z.string() })),
  blockingOpen: z.number(), blockingUnverified: z.number(), untriagedBlockers: z.number(),
  blockingItems: z.array(z.object({
    id: z.string(), reference: z.string(), state: z.string(), persona: z.string(), app: z.string(), summary: z.string(),
    ownerName: z.string().nullish(), trackerUrl: z.string().nullish(), reportedAt: z.string(), reports: z.number(),
  })),
  coverage: z.array(z.object({
    persona: z.enum(PERSONAS), script: z.string(), scriptTitle: z.string(), scriptVersion: z.string(), participants: z.number(),
    signedOff: z.number(), withComments: z.number(), blocked: z.number(), pending: z.number(), complete: z.boolean(),
  })),
  trend: z.array(z.object({ date: z.string(), reported: z.number(), openBlocking: z.number(), resolved: z.number() })),
});
export type Report = z.infer<typeof Report>;

const BASE = '/api/v1/console/uat';
const one = (id: string, action = '') => `${BASE}/feedback/${encodeURIComponent(id)}${action}`;
const items = <T,>(schema: z.ZodType<T>) => z.object({ items: z.array(schema) });

export interface QueueFilter { state: string; blocking?: boolean; persona?: string }
const qs = (f: QueueFilter) => new URLSearchParams(Object.entries(f).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)])).toString();

export const queueQuery = (f: QueueFilter) => queryOptions({
  queryKey: ['console', 'uat', 'queue', f], queryFn: async () => (await http(`${BASE}/feedback?${qs(f)}`, {}, items(Item))).items,
});
export const exportUrl = (f: QueueFilter) => `${BASE}/feedback/export?${qs(f)}`;
export const detailQuery = (id: string) => queryOptions({ queryKey: ['console', 'uat', 'detail', id], queryFn: () => http(one(id), {}, Detail) });
export const ownersQuery = queryOptions({ queryKey: ['console', 'uat', 'owners'], queryFn: async () => (await http(`${BASE}/owners`, {}, items(z.object({ id: z.string(), name: z.string() })))).items });
export const participantsQuery = queryOptions({ queryKey: ['console', 'uat', 'participants'], queryFn: async () => (await http(`${BASE}/participants`, {}, items(Participant))).items });
export const scriptsQuery = queryOptions({ queryKey: ['console', 'uat', 'scripts'], queryFn: async () => (await http(`${BASE}/scripts`, {}, items(Script))).items });
export const reportQuery = queryOptions({ queryKey: ['console', 'uat', 'report'], queryFn: () => http(`${BASE}/go-no-go`, {}, Report) });
export const REPORT_CSV = `${BASE}/go-no-go/export`;

export type Act =
  | { id: string; act: 'moves'; to: State; blocking?: boolean; duplicateOf?: string; note?: string }
  | { id: string; act: 'owner'; ownerId: string | null }
  | { id: string; act: 'tracker'; url: string | null };

export function useAct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, act, ...body }: Act) => http(one(id, `/${act}`), { method: 'POST', body }, Detail),
    onSuccess: d => { qc.setQueryData(detailQuery(d.item.id).queryKey, d); void qc.invalidateQueries({ queryKey: ['console', 'uat'] }); },
  });
}

export function useAddParticipant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { persona: Persona; label: string; contact: string }) => http(`${BASE}/participants`, { method: 'POST', body: p }, Participant),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'uat'] }),
  });
}

export function useParticipantAct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: { id: string; deactivate: true } | { id: string; script: string; outcome: string; comments?: string; blockingIds: string[] }) =>
      'deactivate' in a
        ? http(`${BASE}/participants/${encodeURIComponent(a.id)}/deactivate`, { method: 'POST' }, Participant)
        : http(`${BASE}/participants/${encodeURIComponent(a.id)}/signoffs`, { method: 'POST', body: { script: a.script, outcome: a.outcome, comments: a.comments, blockingIds: a.blockingIds } }, Participant),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'uat'] }),
  });
}
