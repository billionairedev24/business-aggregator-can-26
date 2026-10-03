import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * Pilot onboarding (S-120, api `PilotOnboarding` / `PilotActions`):
 *   GET  /api/v1/console/pilot[?market=]                 PilotBoard
 *   GET  /api/v1/console/pilot/export[?market=]          the same as CSV
 *   GET  /api/v1/console/pilot/{id}                      PilotDetail
 *   POST /api/v1/console/pilot/invites | /{id}/invites | /enrolments · PUT /{id}/owner | /{id}/blocker · POST /{id}/notes
 *   POST /api/v1/console/pilot/{id}/kitchen-visits[/{visit}/photos | /outcome | /cancel]
 */
export const STEPS = ['invited', 'account_created', 'details_complete', 'identity_verified', 'stripe_ready', 'kitchen_visit', 'catalogue_ready', 'approved', 'live'] as const;
export type StepKey = (typeof STEPS)[number];
export const TYPES = ['provider', 'seller', 'kitchen', 'both'] as const;
export const BLOCKER_OWNERS = ['business', 'northline', 'stripe', 'inspector'] as const;

export const Step = z.object({
  key: z.enum(STEPS), state: z.enum(['done', 'todo', 'waiting', 'blocked', 'na']), action: z.string().nullish(), owner: z.string().nullish(),
  params: z.record(z.string(), z.string()).default({}),
});
export type Step = z.infer<typeof Step>;
export const Row = z.object({
  id: z.string(), label: z.string(), businessName: z.string(), businessType: z.string(), marketId: z.string(), merchantId: z.string().nullish(),
  city: z.string().nullish(), stage: z.enum(STEPS), next: Step.nullish(), checklist: z.array(Step), blocked: z.boolean(), blocker: z.string().nullish(),
  blockerOwner: z.string().nullish(), blockerSince: z.string().nullish(), ownerId: z.string().nullish(), ownerName: z.string().nullish(),
  inviteState: z.string().nullish(), listings: z.number(), listingsLive: z.number(), createdAt: z.string(),
});
export type Row = z.infer<typeof Row>;
export const Market = z.object({ id: z.string(), city: z.string(), province: z.string(), stage: z.string() });
export type Market = z.infer<typeof Market>;
export const Board = z.object({
  markets: z.array(Market), marketId: z.string().nullish(), stages: z.record(z.string(), z.number()), live: z.number(), blocked: z.number(), items: z.array(Row),
});
export type Board = z.infer<typeof Board>;
export const Visit = z.object({
  id: z.string(), merchantId: z.string(), scheduledAt: z.string(), inspectorId: z.string().nullish(), inspectorName: z.string().nullish(),
  status: z.enum(['scheduled', 'passed', 'failed', 'cancelled']), checklist: z.record(z.string(), z.string()).default({}), note: z.string().nullish(),
  photoIds: z.array(z.string()).default([]), recordedBy: z.string().nullish(), recordedAt: z.string().nullish(),
});
export type Visit = z.infer<typeof Visit>;
export const Detail = z.object({
  row: Row,
  notes: z.array(z.object({ id: z.string(), authorId: z.string(), authorName: z.string().nullish(), body: z.string(), createdAt: z.string() })),
  invites: z.array(z.object({ id: z.string(), email: z.string(), createdAt: z.string(), expiresAt: z.string(), acceptedAt: z.string().nullish(), state: z.string() })),
  visits: z.array(Visit), visitItems: z.array(z.string()), kitchenVisitRequired: z.boolean(),
});
export type Detail = z.infer<typeof Detail>;
const Invited = z.object({ detail: Detail, link: z.string(), expiresAt: z.string() });
export type Invited = z.infer<typeof Invited>;

const BASE = '/api/v1/console/pilot';
const enc = encodeURIComponent;
export const exportUrl = (market?: string) => `${BASE}/export${market ? `?market=${enc(market)}` : ''}`;
export const photoUrl = (pilotId: string, visitId: string, photoId: string) => `${BASE}/${enc(pilotId)}/kitchen-visits/${enc(visitId)}/photos/${enc(photoId)}`;

export const boardQuery = (market?: string) => queryOptions({
  queryKey: ['console', 'pilot', 'board', market ?? null],
  queryFn: () => http(`${BASE}${market ? `?market=${enc(market)}` : ''}`, {}, Board),
  placeholderData: keepPreviousData,
});
export const detailQuery = (id: string) => queryOptions({ queryKey: ['console', 'pilot', 'detail', id], queryFn: () => http(`${BASE}/${enc(id)}`, {}, Detail) });

/** Writes answer with the business's detail: it replaces the cached one, and the board is read again. */
function usePilotWrite<V, R extends Detail | Invited>(run: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: r => {
      const detail: Detail = 'detail' in r ? (r as Invited).detail : (r as Detail);
      qc.setQueryData(detailQuery(detail.row.id).queryKey, detail);
      void qc.invalidateQueries({ queryKey: ['console', 'pilot', 'board'] });
    },
  });
}

export interface InviteInput { marketId: string; businessType: string; label: string; email: string; language: 'en' | 'fr'; ownerId?: string }
export const useInvite = () => usePilotWrite((v: InviteInput) => http(`${BASE}/invites`, { method: 'POST', body: v }, Invited));
export const useReinvite = () => usePilotWrite((v: { id: string; email?: string; language?: string }) =>
  http(`${BASE}/${enc(v.id)}/invites`, { method: 'POST', body: { email: v.email, language: v.language } }, Invited));
export const useOwner = () => usePilotWrite((v: { id: string; ownerId?: string }) => http(`${BASE}/${enc(v.id)}/owner`, { method: 'PUT', body: { ownerId: v.ownerId } }, Detail));
export const useBlocker = () => usePilotWrite((v: { id: string; text?: string; owner?: string }) =>
  http(`${BASE}/${enc(v.id)}/blocker`, { method: 'PUT', body: { text: v.text, owner: v.owner } }, Detail));
export const useNote = () => usePilotWrite((v: { id: string; body: string }) => http(`${BASE}/${enc(v.id)}/notes`, { method: 'POST', body: { body: v.body } }, Detail));
export const useScheduleVisit = () => usePilotWrite((v: { id: string; at: string; inspectorId?: string; inspectorName?: string }) =>
  http(`${BASE}/${enc(v.id)}/kitchen-visits`, { method: 'POST', body: { at: v.at, inspectorId: v.inspectorId, inspectorName: v.inspectorName } }, Detail));
export const useVisitOutcome = () => usePilotWrite((v: { id: string; visitId: string; outcome: 'passed' | 'failed'; checklist: Record<string, string>; note?: string }) =>
  http(`${BASE}/${enc(v.id)}/kitchen-visits/${enc(v.visitId)}/outcome`, { method: 'POST', body: { outcome: v.outcome, checklist: v.checklist, note: v.note } }, Detail));
export const useCancelVisit = () => usePilotWrite((v: { id: string; visitId: string }) =>
  http(`${BASE}/${enc(v.id)}/kitchen-visits/${enc(v.visitId)}/cancel`, { method: 'POST' }, Detail));
export const useVisitPhoto = () => usePilotWrite((v: { id: string; visitId: string; file: File }) => {
  const form = new FormData();
  form.append('file', v.file);
  return http(`${BASE}/${enc(v.id)}/kitchen-visits/${enc(v.visitId)}/photos`, { method: 'POST', body: form }, Detail);
});
