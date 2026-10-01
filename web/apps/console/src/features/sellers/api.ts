import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

const Flag = z.object({
  kind: z.enum(['quality_below', 'disputes_above', 'trust_flag', 'check_expiring', 'check_due', 'check_pending']),
  rule: z.string().nullish(), checkType: z.string().nullish(), registry: z.string().nullish(), status: z.string().nullish(),
  value: z.number().nullish(), floor: z.number().nullish(), days: z.number().nullish(),
});
export type Flag = z.infer<typeof Flag>;

/** One business (S-82; api `ViewSellers.Row`). */
export const Seller = z.object({
  id: z.string(), name: z.string(), category: z.object({ id: z.string(), names: z.record(z.string(), z.string()) }).nullish(),
  type: z.enum(['provider', 'seller', 'kitchen', 'both']), province: z.string().nullish(), city: z.string().nullish(),
  tier: z.enum(['registered', 'trusted', 'master']).nullish(), status: z.enum(['applicant', 'pending', 'active', 'paused', 'suspended']).nullish(),
  quality: z.number().nullish(), gmv90Cents: z.number(), disputeRate: z.number().nullish(), flags: z.array(Flag),
});
export type Seller = z.infer<typeof Seller>;

export const Directory = z.object({ asOf: z.string(), active: z.number(), atRisk: z.number(), items: z.array(Seller), truncated: z.boolean() });
export type Directory = z.infer<typeof Directory>;

const Measure = z.object({ value: z.number().nullish(), floor: z.number().nullish() });
export const Check = z.object({ id: z.string(), checkType: z.string(), registry: z.string().nullish(), reference: z.string().nullish(), status: z.string(), expiresAt: z.string().nullish() });
export type Check = z.infer<typeof Check>;
const Trail = z.object({ id: z.string(), action: z.enum(['suspended', 'reinstated', 'reverification_required', 'tier_changed']), reason: z.string(),
  detail: z.record(z.string(), z.string()), actorName: z.string().nullish(), actorRole: z.string(), at: z.string() });
export type Trail = z.infer<typeof Trail>;

export const Detail = z.object({
  asOf: z.string(), seller: Seller, joinedAt: z.string(), approvedAt: z.string().nullish(), stripeAccount: z.string().nullish(),
  ratingAverage: z.number(), ratingCount: z.number(), quality: Measure, onTime: Measure, disputes: Measure,
  signals: z.array(z.object({ key: z.string(), value: z.number(), bar: z.number(), barFloor: z.number(), inverted: z.boolean() })),
  checks: z.array(Check), trail: z.array(Trail),
});
export type Detail = z.infer<typeof Detail>;

export interface SellersFilter { q?: string; province?: string; market?: string }

export const sellersQuery = ({ q, province, market }: SellersFilter) => queryOptions({
  queryKey: ['console', 'sellers', q ?? null, province ?? null, market ?? null],
  queryFn: () => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    if (province) p.set('province', province);
    if (market) p.set('market', market);
    const qs = p.toString();
    return http(`/api/v1/console/sellers${qs ? `?${qs}` : ''}`, {}, Directory);
  },
});
export const sellerQuery = (id: string) => queryOptions({
  queryKey: ['console', 'sellers', 'detail', id],
  queryFn: () => http(`/api/v1/console/sellers/${encodeURIComponent(id)}`, {}, Detail),
});

export type OversightAction = 'suspend' | 'reinstate' | 'reverification' | 'tier';
export interface OversightInput { sellerId: string; action: OversightAction; reason: string; tier?: string; verificationId?: string }

/** `POST /api/v1/console/merchants/{businessId}/{suspend|reinstate|reverification|tier}` (audited, the owners are emailed). */
export function useOversight() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sellerId, action, reason, tier, verificationId }: OversightInput) => http(
      `/api/v1/console/merchants/${encodeURIComponent(sellerId)}/${action}`,
      { method: 'POST', body: action === 'tier' ? { tier, reason } : action === 'reverification' ? { verificationId, reason } : { reason } },
    ),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'sellers'] }),
  });
}
