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
export const Recommendation = z.enum(['approve', 'reject', 'request_info']);
export type Recommendation = z.infer<typeof Recommendation>;
/**
 * A stage-1 review (two-person review, 2026-10-18; api `VerificationQueue.ReviewRow`): the reviewer's recommendation
 * and internal note; `decisionId` is set once a decision closed its round.
 */
export const Review = z.object({
  id: z.string(), recommendation: Recommendation, checkKeys: z.array(z.string()), note: z.string().nullish(),
  reviewedBy: z.string(), reviewerName: z.string(), reviewedAt: z.string(), decisionId: z.string().nullish(),
});
export type Review = z.infer<typeof Review>;

/** One application (api `VerificationQueue.ApplicationRow`). */
export const Application = z.object({
  merchantId: z.string(), businessName: z.string(), legalName: z.string(), type: z.string().nullish(), structure: z.string().nullish(),
  categories: z.array(z.string()), province: z.string().nullish(), city: z.string().nullish(), status: z.string(),
  submittedAt: z.string().nullish(), checks: z.array(Check), risk: z.enum(['low', 'medium', 'high']),
  decision: Decision.nullish(), decidedAt: z.string().nullish(),
  /** While pending: `review` (stage 1, nobody has recommended yet) or `decision` (stage 2); the api's, not derived. */
  stage: z.enum(['review', 'decision']).nullish(),
  /** The current review of the open round. */
  review: Review.nullish(),
});
export type Application = z.infer<typeof Application>;

/** `GET /api/v1/console/verification/applications` (S-79). */
export const Queue = z.object({ items: z.array(Application), pending: z.number(), medianDecisionHours: z.number().nullish(), fourEyes: z.boolean().default(true) });
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
export const DecisionRow = z.object({ id: z.string(), decision: Decision, checkKeys: z.array(z.string()), note: z.string().nullish(), decidedBy: z.string(), decidedAt: z.string(), reviewId: z.string().nullish() });
/** `fourEyes`: a decision needs another person's review first (`northline.onboarding.four-eyes.enabled`). */
export const Detail = z.object({
  application: Application, owners: z.array(OwnerReview), registryReviews: z.array(RegistryReview), reviews: z.array(Review).default([]),
  decisions: z.array(DecisionRow), fourEyes: z.boolean().default(true),
});
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

/** Stage 1 (two-person review): `POST …/{id}/review` — the recommendation, the checks to redo, a note for the approver. */
export const useReviewApplication = () => useDecision((v: { id: string; recommendation: Recommendation; checkKeys?: string[]; note?: string }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/review`, { method: 'POST', body: { recommendation: v.recommendation, checkKeys: v.checkKeys, note: v.note } }, Detail));

export const useDecideIdentity = () => useDecision((v: { id: string; checkId: string; decision: 'approve' | 'reject'; note?: string }) =>
  http(`${BASE}/${encodeURIComponent(v.id)}/identity-reviews/${encodeURIComponent(v.checkId)}/decision`, { method: 'POST', body: { decision: v.decision, note: v.note } }, Detail));

/** S-23 `POST /api/v1/console/registry-reviews/{id}/decision`. */
export const useDecideRegistry = () => useDecision((v: { reviewId: string; decision: 'approve' | 'reject'; note?: string }) =>
  http(`/api/v1/console/registry-reviews/${encodeURIComponent(v.reviewId)}/decision`, { method: 'POST', body: { decision: v.decision, note: v.note } }));

// ── S-158: the registry check queue (rows needing a human, re-check, outcome) ──────────────────────────────────────
/** An open S-23 review with its evidence (api `RegistryReviews.ReviewView`). */
export const RegistryQueueRow = RegistryReview.extend({
  merchantId: z.string(), businessName: z.string(), subject: z.string(), recordNumber: z.string().nullish(),
  recordExpiresOn: z.string().nullish(), reference: z.string().nullish(), checkedAt: z.string(),
});
export type RegistryQueueRow = z.infer<typeof RegistryQueueRow>;
export const registryQueueQuery = queryOptions({
  queryKey: ['console', 'verify', 'registry'],
  queryFn: async () => (await http('/api/v1/console/registry-reviews?limit=200', {}, z.object({ items: z.array(RegistryQueueRow) }))).items,
});
export const Rechecked = z.object({ verificationStatus: z.string(), checks: z.array(RegistryQueueRow) });
export type Rechecked = z.infer<typeof Rechecked>;
/** `POST /api/v1/console/registry-reviews/{id}/recheck`: the lookup runs again; the open review is superseded. */
export const useRecheckRegistry = () => useDecision((reviewId: string) =>
  http(`/api/v1/console/registry-reviews/${encodeURIComponent(reviewId)}/recheck`, { method: 'POST' }, Rechecked));
/** The queue's decision with what the agent checked (reference, the licence's expiry). */
export const useDecideRegistryWithEvidence = () => useDecision((v: { reviewId: string; decision: 'approve' | 'reject'; reference?: string; expiresOn?: string; note?: string }) =>
  http(`/api/v1/console/registry-reviews/${encodeURIComponent(v.reviewId)}/decision`, { method: 'POST', body: { decision: v.decision, reference: v.reference, expiresOn: v.expiresOn, note: v.note } }));
