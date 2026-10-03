import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import { MerchantStatus, MerchantType, businessesQuery } from '../shell/api';
import type { BusinessProfile } from './businessFields';

/** `GET /api/v1/merchants/{id}/onboarding` (and every onboarding write). See DECISIONS.md › Onboarding API. */
export const Document = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), sizeBytes: z.number() });
export type Document = z.infer<typeof Document>;
export const Check = z.object({
  id: z.string(),
  key: z.string(),
  checkType: z.string(),
  action: z.enum(['instant', 'identity', 'number', 'upload', 'sign', 'choose', 'slot']),
  registry: z.string().nullish(),
  status: z.enum(['todo', 'submitted', 'verified', 'expired', 'rejected']),
  reference: z.string().nullish(),
  document: Document.nullish(),
  expiresOn: z.string().nullish(),
  updatedAt: z.string(),
});
export type Check = z.infer<typeof Check>;
export const PrincipalRole = z.enum(['owner', 'partner', 'partner_signing', 'director', 'officer', 'shareholder', 'chair', 'president', 'treasurer', 'secretary']);
export type PrincipalRole = z.infer<typeof PrincipalRole>;
export const Structure = z.enum(['sole', 'partnership', 'corp_ab', 'corp_fed', 'corp_ex', 'coop', 'nonprofit']);
export type Structure = z.infer<typeof Structure>;
export const Onboarding = z.object({
  merchantId: z.string(),
  type: MerchantType,
  status: MerchantStatus,
  step: z.enum(['account', 'business', 'verification', 'review', 'page', 'listings', 'done']),
  province: z.string().nullish(),
  workEmail: z.string().nullish(),
  businessTermsAccepted: z.boolean(),
  displayName: z.string(),
  city: z.string().nullish(),
  business: z.object({
    displayName: z.string(),
    legalName: z.string(),
    structure: Structure.nullish(),
    gstNumber: z.string().nullish(),
    legalDetails: z.record(z.string(), z.unknown()),
    principals: z.array(z.object({ legalName: z.string(), role: PrincipalRole, ownershipPct: z.number().nullish() })),
    categories: z.array(z.object({ id: z.string(), name: z.string(), regulator: z.string().nullish(), suggested: z.boolean() })),
    profile: z.record(z.string(), z.unknown()),
    documents: z.array(Document).default([]),
  }).nullish(),
  checklist: z.array(Check),
  checksComplete: z.number(),
  submittedAt: z.string().nullish(),
  approvedAt: z.string().nullish(),
});
export type Onboarding = z.infer<typeof Onboarding>;

export const onboardingQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'onboarding'],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/onboarding`, {}, Onboarding),
});

/** `GET /api/v1/onboarding/taxonomy?type=` — grouped categories served from catalogue.categories. */
export const Taxonomy = z.object({
  type: MerchantType,
  limit: z.number(),
  groups: z.array(z.object({ id: z.string(), name: z.string(), note: z.string().default(''), items: z.array(z.object({ id: z.string(), name: z.string(), regulator: z.string().nullish() })) })),
});
export type Taxonomy = z.infer<typeof Taxonomy>;
export const taxonomyQuery = (type: MerchantType, lang: string) => queryOptions({
  queryKey: ['onboarding', 'taxonomy', type, lang],
  queryFn: () => http(`/api/v1/onboarding/taxonomy?type=${type}`, { headers: { 'accept-language': lang === 'fr' ? 'fr-CA' : 'en-CA' } }, Taxonomy),
  staleTime: 10 * 60_000,
});

export interface AccountInput { type: MerchantType; province: string; workEmail?: string; businessTermsAccepted?: boolean; pilotInvite?: string }

/** S-120 `GET /api/v1/pilot-invites/{token}`: what a pilot invite pre-fills. */
export const PilotInvite = z.object({
  businessType: MerchantType, label: z.string(), marketId: z.string(), city: z.string(), province: z.string(), expiresAt: z.string(),
  state: z.enum(['pending', 'expired', 'accepted', 'revoked']),
});
export type PilotInvite = z.infer<typeof PilotInvite>;
export const pilotInviteQuery = (token: string) => queryOptions({
  queryKey: ['pilot-invite', token],
  queryFn: () => http(`/api/v1/pilot-invites/${encodeURIComponent(token)}`, {}, PilotInvite),
  retry: false,
});
export interface PrincipalInput { legalName: string; role: PrincipalRole; ownershipPct?: number | null }
export interface BusinessInput {
  displayName: string; legalName: string; structure: Structure; gstNumber?: string;
  legalDetails: Record<string, unknown>; principals: PrincipalInput[]; categoryIds: string[]; suggestedCategories: string[]; profile: BusinessProfile;
}
export interface CompleteInput { reference?: string; documentId?: string; expiresOn?: string; choice?: string }

function useOnboardingWrite<V>(merchantId: string | undefined, run: (v: V) => Promise<Onboarding>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: data => {
      qc.setQueryData(onboardingQuery(data.merchantId).queryKey, data);
      void qc.invalidateQueries({ queryKey: businessesQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['merchant', data.merchantId], exact: true });
      if (merchantId) void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'storefront'] });
    },
  });
}

/** End of the Account step: creates the applicant (`POST /api/v1/merchants`). */
export const useStartOnboarding = () => useOnboardingWrite<AccountInput>(undefined, body => http('/api/v1/merchants', { method: 'POST', body }, Onboarding));
export const useUpdateAccount = (merchantId: string) => useOnboardingWrite<AccountInput>(merchantId, body => http(`/api/v1/merchants/${merchantId}/onboarding/account`, { method: 'PUT', body }, Onboarding));
export const useSaveBusiness = (merchantId: string) => useOnboardingWrite<BusinessInput>(merchantId, body => http(`/api/v1/merchants/${merchantId}/onboarding/business`, { method: 'PUT', body }, Onboarding));
export const useAdvanceStep = (merchantId: string) => useOnboardingWrite<'review' | 'page' | 'listings' | 'done'>(merchantId, step => http(`/api/v1/merchants/${merchantId}/onboarding`, { method: 'PATCH', body: { step } }, Onboarding));
export const useSubmitApplication = (merchantId: string) => useOnboardingWrite<void>(merchantId, () => http(`/api/v1/merchants/${merchantId}/onboarding/submit`, { method: 'POST' }, Onboarding));
export const useCompleteCheck = (merchantId: string) =>
  useOnboardingWrite<{ id: string } & CompleteInput>(merchantId, ({ id, ...body }) => http(`/api/v1/merchants/${merchantId}/verifications/${id}/complete`, { method: 'POST', body }, Onboarding));
/** DEV ONLY ("Simulate approval →"): the api exposes it under the `local` profile only. */
export const useSimulateApproval = (merchantId: string) => useOnboardingWrite<void>(merchantId, () => http(`/api/v1/dev/merchants/${merchantId}/approve`, { method: 'POST' }, Onboarding));

/** S-22 · `GET /api/v1/merchants/{id}/identity-checks`: every owner who verifies with Stripe Identity (owner-only). */
export const OwnerIdentity = z.object({
  principalId: z.string(),
  legalName: z.string(),
  role: PrincipalRole,
  ownershipPct: z.number().nullish(),
  you: z.boolean(),
  status: z.enum(['not_started', 'pending', 'processing', 'verified', 'retry', 'review', 'canceled']),
  delivery: z.enum(['self', 'email']).nullish(),
  emailMasked: z.string().nullish(),
  lastError: z.string().nullish(),
  nameMatch: z.enum(['match', 'mismatch', 'unavailable']).nullish(),
  dobMatch: z.enum(['match', 'mismatch', 'unavailable']).nullish(),
  attempts: z.number(),
  updatedAt: z.string().nullish(),
});
export type OwnerIdentity = z.infer<typeof OwnerIdentity>;
const OwnerIdentities = z.object({ items: z.array(OwnerIdentity) });
const SessionStarted = z.object({ owner: OwnerIdentity, url: z.string().nullish() });

/** While Stripe is checking or a link is out, the list refreshes itself (webhooks move the owners). */
export const ownerIdentityQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'identity-checks'],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/identity-checks`, {}, OwnerIdentities).then(r => r.items),
  refetchInterval: q => (q.state.data?.some(o => o.status === 'pending' || o.status === 'processing') ? 5000 : false),
});

export interface StartSessionInput { principalId: string; delivery: 'self' | 'email'; email?: string }

/** `POST …/identity-checks/{principalId}/session` → Stripe's hosted flow (self) or an emailed link. */
export function useStartOwnerSession(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ principalId, ...body }: StartSessionInput) =>
      http(`/api/v1/merchants/${merchantId}/identity-checks/${principalId}/session`, { method: 'POST', body }, SessionStarted),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ownerIdentityQuery(merchantId).queryKey });
      void qc.invalidateQueries({ queryKey: onboardingQuery(merchantId).queryKey });
    },
  });
}
