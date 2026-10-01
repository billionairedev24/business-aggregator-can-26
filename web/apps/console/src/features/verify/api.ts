import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

const CheckState = z.enum(['passed', 'review', 'waiting', 'failed']);
export const Check = z.object({
  id: z.string(), key: z.string(), type: z.string(), registry: z.string().nullish(), status: z.string(), state: CheckState,
  reference: z.string().nullish(), expiresAt: z.string().nullish(),
});
export type Check = z.infer<typeof Check>;
const Decision = z.enum(['approved', 'info_requested']);

/** One application (api `VerificationQueue.ApplicationRow`). */
export const Application = z.object({
  merchantId: z.string(), businessName: z.string(), legalName: z.string(), type: z.string().nullish(), structure: z.string().nullish(),
  categories: z.array(z.string()), province: z.string().nullish(), city: z.string().nullish(), status: z.string(),
  submittedAt: z.string().nullish(), checks: z.array(Check), risk: z.enum(['low', 'medium', 'high']),
  decision: Decision.nullish(), decidedAt: z.string().nullish(),
});
export type Application = z.infer<typeof Application>;

/** `GET /api/v1/console/verification/applications` (S-79). */
export const Queue = z.object({ items: z.array(Application), pending: z.number(), medianDecisionHours: z.number().nullish() });
export type Queue = z.infer<typeof Queue>;

export const OwnerReview = z.object({
  checkId: z.string().nullish(), principalName: z.string(), role: z.string(), ownershipPct: z.number().nullish(), status: z.string(),
  nameMatch: z.string().nullish(), dobMatch: z.string().nullish(), lastError: z.string().nullish(), attempts: z.number(),
  reviewNote: z.string().nullish(), reviewedAt: z.string().nullish(),
});
export type OwnerReview = z.infer<typeof OwnerReview>;
/** An S-23 registry lookup that went to an agent (api `RegistryReviews.ReviewView`). */
export const RegistryReview = z.object({
  id: z.string(), checkKey: z.string(), source: z.string(), registry: z.string().nullish(), queryNumber: z.string(),
  expectedName: z.string().nullish(), outcome: z.string(), reasons: z.array(z.string()), recordName: z.string().nullish(),
  recordStatus: z.string().nullish(), reviewState: z.string().nullish(), reviewNote: z.string().nullish(),
});
export type RegistryReview = z.infer<typeof RegistryReview>;
export const DecisionRow = z.object({ id: z.string(), decision: Decision, checkKeys: z.array(z.string()), note: z.string().nullish(), decidedBy: z.string(), decidedAt: z.string() });
export const Detail = z.object({ application: Application, owners: z.array(OwnerReview), registryReviews: z.array(RegistryReview), decisions: z.array(DecisionRow) });
export type Detail = z.infer<typeof Detail>;

const BASE = '/api/v1/console/verification/applications';
const qs = (f: PlaceFilter) => {
  const q = new URLSearchParams();
  if (f.province) q.set('province', f.province);
  if (f.market) q.set('market', f.market);
  const s = q.toString();
  return s ? `?${s}` : '';
};

export const queueQuery = (f: PlaceFilter) => queryOptions({
  queryKey: ['console', 'verify', 'queue', f.province ?? null, f.market ?? null],
  queryFn: () => http(`${BASE}${qs(f)}`, {}, Queue),
  placeholderData: keepPreviousData,
});
export const detailQuery = (id: string) => queryOptions({
  queryKey: ['console', 'verify', 'detail', id],
  queryFn: () => http(`${BASE}/${encodeURIComponent(id)}`, {}, Detail),
});

/** Every decision refreshes the queue and the application (the api answers with the application). */
function useDecision<V>(post: (v: V) => Promise<Detail | unknown>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: post,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'verify'] }),
  });
}

export const useDecideApplication = () => useDecision((v: { id: string; decision: 'approve' | 'request_info'; checkKeys?: string[]; note?: string }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/decision`, { method: 'POST', body: { decision: v.decision, checkKeys: v.checkKeys, note: v.note } }, Detail));

export const useDecideIdentity = () => useDecision((v: { id: string; checkId: string; decision: 'approve' | 'reject'; note?: string }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/identity-reviews/${encodeURIComponent(v.checkId)}/decision`, { method: 'POST', body: { decision: v.decision, note: v.note } }, Detail));

/** S-23 `POST /api/v1/console/registry-reviews/{id}/decision`. */
export const useDecideRegistry = () => useDecision((v: { reviewId: string; decision: 'approve' | 'reject'; note?: string }) =>
  http(`/api/v1/console/registry-reviews/${encodeURIComponent(v.reviewId)}/decision`, { method: 'POST', body: { decision: v.decision, note: v.note } }));
