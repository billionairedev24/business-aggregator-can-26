import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * Stripe & compliance — `GET /api/v1/merchants/{id}/compliance` (merchants module) and the owner's actions:
 * renewal uploads, accepting the current platform obligations, Stripe Express dashboard / onboarding links.
 */
export const RequirementKind = z.enum(['identity', 'business', 'bank', 'owners', 'annual_reverification']);
export const Requirement = z.object({ kind: RequirementKind, state: z.enum(['verified', 'pending', 'due', 'past_due']), dueAt: z.string().nullish() });
export type Requirement = z.infer<typeof Requirement>;

export const ComplianceDoc = z.object({
  id: z.string(), checkType: z.string(), checkKey: z.string().nullish(), registry: z.string().nullish(), reference: z.string().nullish(),
  label: z.string().nullish(), status: z.enum(['todo', 'submitted', 'verified', 'expired', 'rejected']), expiresAt: z.string().nullish(),
  verifiedAt: z.string().nullish(), submittedAt: z.string().nullish(), pausesAt: z.string().nullish(), due: z.boolean(), dueSoon: z.boolean(),
});
export type ComplianceDoc = z.infer<typeof ComplianceDoc>;

export const TaxRow = z.object({ jurisdiction: z.string(), collectedCents: z.number(), handling: z.enum(['remitted_by_northline', 'not_selling', 'charged_on_invoice']) });
export type TaxRow = z.infer<typeof TaxRow>;

export const Obligations = z.object({ currentVersion: z.string(), acceptedVersion: z.string().nullish(), acceptedAt: z.string().nullish(), upToDate: z.boolean() });
export type Obligations = z.infer<typeof Obligations>;

export const Compliance = z.object({
  business: z.object({
    type: z.enum(['provider', 'seller', 'kitchen', 'both']), displayName: z.string(), legalName: z.string(), businessNumber: z.string().nullish(),
    province: z.string().nullish(), requiredFor: z.string().nullish(), ownerName: z.string().nullish(), takeRateBps: z.number().nullish(),
  }),
  stripe: z.object({
    status: z.enum(['connected', 'not_connected', 'unavailable']), accountId: z.string().nullish(), type: z.string().nullish(),
    chargesEnabled: z.boolean(), payoutsEnabled: z.boolean(), requirements: z.array(Requirement), bankLabel: z.string().nullish(),
    statementDescriptor: z.string().nullish(), payoutInterval: z.string().nullish(), payoutWeekday: z.string().nullish(), instantPayouts: z.boolean(),
  }),
  taxPeriod: z.string(),
  tax: z.array(TaxRow),
  documents: z.array(ComplianceDoc),
  obligations: Obligations,
  dueCount: z.number(),
});
export type Compliance = z.infer<typeof Compliance>;

const base = (m: string) => `/api/v1/merchants/${m}/compliance`;
export const complianceKey = (m: string) => ['merchant', m, 'compliance'] as const;
export const complianceQuery = (m: string) => queryOptions({ queryKey: complianceKey(m), queryFn: () => http(base(m), {}, Compliance) });

function useRefresh(m: string) {
  const qc = useQueryClient();
  return () => Promise.all([qc.invalidateQueries({ queryKey: complianceKey(m) }), qc.invalidateQueries({ queryKey: ['merchant', m, 'nav-badges'] })]);
}

/** Uploads a renewal document (PDF, PNG or JPEG ≤ 10 MB); the row turns "in review" at once. */
export function useRenewDocument(m: string) {
  const qc = useQueryClient();
  const refresh = useRefresh(m);
  return useMutation({
    mutationFn: ({ id, file }: { id: string; file: File }) => {
      const form = new FormData();
      form.append('file', file);
      return http(`${base(m)}/verifications/${id}/renewal`, { method: 'POST', body: form }, ComplianceDoc);
    },
    onSuccess: doc => {
      qc.setQueryData<Compliance>(complianceKey(m), c => c && { ...c, documents: c.documents.map(d => (d.id === doc.id ? doc : d)), dueCount: c.documents.filter(d => (d.id === doc.id ? doc.due : d.due)).length });
      void refresh();
    },
  });
}

export function useAcceptObligations(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (version: string) => http(`${base(m)}/obligations`, { method: 'POST', body: { version } }, Obligations),
    onSuccess: obligations => qc.setQueryData<Compliance>(complianceKey(m), c => c && { ...c, obligations }),
  });
}

/** `dashboard` = Stripe Express dashboard (new tab); `update` = Stripe-hosted onboarding (identity document, bank). */
export function useStripeLink(m: string) {
  return useMutation({ mutationFn: (kind: 'dashboard' | 'update') => http(`${base(m)}/stripe-links`, { method: 'POST', body: { kind } }, z.object({ url: z.string() })) });
}
