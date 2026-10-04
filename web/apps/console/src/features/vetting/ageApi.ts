import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import type { PlaceFilter } from '../shell/PlaceFilters';

/** Age-restricted sales (2026-10-04): licences in review (merchants module) and trust & safety's report (restricted). */
export const Licence = z.object({
  id: z.string(), merchantId: z.string(), ageClass: z.enum(['alcohol', 'tobacco', 'cannabis']), province: z.string(), licenceNumber: z.string(),
  documentId: z.string(), expiresOn: z.string(), status: z.enum(['pending', 'approved', 'rejected', 'expired', 'replaced']), submittedAt: z.string(),
  decidedAt: z.string().nullish(), rejectReason: z.string().nullish(), note: z.string().nullish(),
});
export type Licence = z.infer<typeof Licence>;
export const LicenceItem = z.object({ licence: Licence, businessName: z.string(), businessProvince: z.string().nullish() });
export type LicenceItem = z.infer<typeof LicenceItem>;

export const LICENCE_REASONS = ['unreadable', 'wrong_class', 'wrong_business', 'expired', 'not_valid', 'other'] as const;
export type LicenceReason = (typeof LICENCE_REASONS)[number];

export const licencesQuery = (f: PlaceFilter, status: string) => queryOptions({
  queryKey: ['console', 'vetting', 'licences', f.province ?? null, f.market ?? null, status],
  queryFn: () => {
    const q = new URLSearchParams({ status });
    if (f.province) q.set('province', f.province);
    if (f.market) q.set('market', f.market);
    return http(`/api/v1/console/vetting/licences?${q}`, {}, z.object({ items: z.array(LicenceItem) }));
  },
  placeholderData: keepPreviousData,
});

export const documentHref = (id: string) => `/api/v1/console/vetting/licences/${encodeURIComponent(id)}/document`;

export function useDecideLicence() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: string; decision: 'approve' | 'reject'; reason?: LicenceReason; note?: string }) =>
      http(`/api/v1/console/vetting/licences/${encodeURIComponent(v.id)}/decision`, { method: 'POST', body: { decision: v.decision, reason: v.reason, note: v.note } }, LicenceItem),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'vetting', 'licences'] }),
  });
}

const Counts = z.record(z.string(), z.number());
export const Report = z.object({
  from: z.string(), to: z.string(), province: z.string().nullish(), verifications: Counts, failures: Counts, handoffs: Counts, refusals: Counts, byPlace: Counts,
  recent: z.array(z.object({ checkId: z.string(), orderId: z.string(), orderType: z.string(), province: z.string().nullish(), requiredAge: z.number(), actorRole: z.string(), place: z.string(), reason: z.string(), at: z.string() })),
});
export type Report = z.infer<typeof Report>;

export const reportQuery = (province?: string) => queryOptions({
  queryKey: ['console', 'vetting', 'age-checks', province ?? null],
  queryFn: () => http(`/api/v1/console/vetting/age-checks${province ? `?province=${encodeURIComponent(province)}` : ''}`, {}, Report),
});

export const AgeRule = z.object({
  province: z.string(), ageClass: z.string(), minimumAge: z.number(), deliveryAllowed: z.boolean(), pickupAllowed: z.boolean(),
  deliveryFrom: z.string().nullish(), deliveryUntil: z.string().nullish(), source: z.string(), confirmed: z.boolean(),
});
export const rulesQuery = queryOptions({
  queryKey: ['console', 'vetting', 'age-rules'],
  queryFn: () => http('/api/v1/console/vetting/age-rules', {}, z.object({ items: z.array(AgeRule) })),
  staleTime: 5 * 60_000,
});
