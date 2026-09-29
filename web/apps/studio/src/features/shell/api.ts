import { queryOptions, useQuery } from '@tanstack/react-query';
import { useParams } from '@tanstack/react-router';
import { z } from 'zod';
import { ApiError, http } from '../../lib/http';

export const MerchantType = z.enum(['provider', 'seller', 'kitchen', 'both']);
export type MerchantType = z.infer<typeof MerchantType>;
export const MerchantTier = z.enum(['registered', 'trusted', 'master']);
export const MerchantStatus = z.enum(['applicant', 'pending', 'active', 'paused', 'suspended']);

/** GET /api/v1/me/businesses — every business the signed-in user belongs to (Switch business). */
export const Business = z.object({ id: z.string(), displayName: z.string(), type: MerchantType, tier: MerchantTier.nullish(), city: z.string().nullish(), status: MerchantStatus.nullish(), role: z.string() });
export type Business = z.infer<typeof Business>;
export const businessesQuery = queryOptions({ queryKey: ['me', 'businesses'], queryFn: () => http('/api/v1/me/businesses', {}, z.array(Business)) });

/** GET /api/v1/merchants/{id} — Studio header summary. */
export const MerchantSummary = z.object({ id: z.string(), displayName: z.string(), type: MerchantType, tier: MerchantTier.nullish(), city: z.string().nullish(), status: MerchantStatus.nullish(), role: z.string().nullish(), teamCount: z.number().nullish() });
export type MerchantSummary = z.infer<typeof MerchantSummary>;
export const merchantQuery = (merchantId: string) => queryOptions({ queryKey: ['merchant', merchantId], queryFn: () => http(`/api/v1/merchants/${merchantId}`, {}, MerchantSummary) });

/** GET /api/v1/merchants/{id}/nav-badges — screen key → badge text ("3", "4 to pack", "Fri"). Missing endpoint = no badges. */
export const navBadgesQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'nav-badges'],
  queryFn: async () => { try { return await http(`/api/v1/merchants/${merchantId}/nav-badges`, {}, z.record(z.string(), z.string())); } catch (e) { if (e instanceof ApiError && e.status === 404) return {}; throw e; } },
  staleTime: 30_000,
});

/** Current merchant id from the /b/$merchantId route. */
export const useMerchantId = () => useParams({ from: '/b/$merchantId' }).merchantId;
/** Current merchant (header summary) — loaded by the studio layout, so it is always cached inside studio screens. */
export function useMerchant() {
  const id = useMerchantId();
  return useQuery(merchantQuery(id)).data!;
}
/** Team role of the signed-in user in the current business (owner, manager, technician, cook, bookkeeper …). */
export const useRole = () => useMerchant()?.role ?? 'owner';
