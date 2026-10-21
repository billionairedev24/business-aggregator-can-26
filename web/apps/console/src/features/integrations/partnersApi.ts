import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * S-158 partners (S-30's `partner:<name>` clients, registered here instead of in configuration). Admins (action
 * `keys`) register, rotate, disable / enable and ask businesses; each owner approves in the Studio.
 *
 *   GET/POST /api/v1/console/partners · POST …/{id}/rotate | /disable | /enable · POST …/{id}/businesses
 *   DELETE …/{id}/businesses/{merchantId}
 */
export const PARTNER_SCOPES = ['api.read', 'api.write'] as const;
export const PartnerBinding = z.object({
  merchantId: z.string(), businessName: z.string().nullish(), state: z.enum(['requested', 'approved', 'declined', 'revoked']),
  requestedAt: z.string(), decidedAt: z.string().nullish(),
});
export const Partner = z.object({
  id: z.string(), name: z.string(), clientId: z.string(), displayName: z.string(), jwkSetUrl: z.string(), scopes: z.array(z.string()),
  tokenTtlSeconds: z.number(), state: z.enum(['active', 'disabled']), keyVersion: z.number(), rotatedAt: z.string().nullish(),
  createdAt: z.string(), businesses: z.array(PartnerBinding),
});
export type Partner = z.infer<typeof Partner>;

const KEY = ['console', 'partners'] as const;
export const partnersQuery = queryOptions({
  queryKey: KEY,
  queryFn: async () => (await http('/api/v1/console/partners', {}, z.object({ items: z.array(Partner) }))).items,
});

function usePartnerMutation<V>(fn: (v: V) => Promise<Partner>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: KEY }) });
}
export interface NewPartner { name: string; displayName: string; jwkSetUrl: string; scopes: string[]; tokenTtlSeconds?: number; merchantIds: string[] }
export const useRegisterPartner = () => usePartnerMutation((v: NewPartner) => http('/api/v1/console/partners', { method: 'POST', body: v }, Partner));
export const useRotatePartner = () => usePartnerMutation((v: { id: string; jwkSetUrl?: string }) =>
  http(`/api/v1/console/partners/${encodeURIComponent(v.id)}/rotate`, { method: 'POST', body: v.jwkSetUrl ? { jwkSetUrl: v.jwkSetUrl } : {} }, Partner));
export const usePartnerState = () => usePartnerMutation((v: { id: string; action: 'disable' | 'enable' }) =>
  http(`/api/v1/console/partners/${encodeURIComponent(v.id)}/${v.action}`, { method: 'POST' }, Partner));
export const useAskBusinesses = () => usePartnerMutation((v: { id: string; merchantIds: string[] }) =>
  http(`/api/v1/console/partners/${encodeURIComponent(v.id)}/businesses`, { method: 'POST', body: { merchantIds: v.merchantIds } }, Partner));
export const useRemoveBusiness = () => usePartnerMutation((v: { id: string; merchantId: string }) =>
  http(`/api/v1/console/partners/${encodeURIComponent(v.id)}/businesses/${encodeURIComponent(v.merchantId)}`, { method: 'DELETE' }, Partner));

/** Business ids typed one per line or separated by commas / spaces. */
export const parseIds = (text: string) => [...new Set(text.split(/[\s,]+/).map(s => s.trim()).filter(Boolean))];
