import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import { CommerceProvider, catalogueKeys } from '../catalogue/api';

/**
 * S-158, Settings › API & integrations: two-way stock with the connected Shopify / Square / Lightspeed store (the
 * location Northline sells from, the changes the store refused) and partner access (the partners Northline registered
 * that asked to act for this business, and the owner's answer).
 *
 *   GET  /api/v1/merchants/{m}/listings/integrations/{provider}/stock | /locations · PUT …/{provider}/location
 *   POST /api/v1/merchants/{m}/listings/integrations/stock-changes/{id}/retry | /dismiss
 *   GET  /api/v1/merchants/{m}/settings/partners · POST …/partners/{partnerId}/decision {decision}
 */
const integrations = (m: string) => `/api/v1/merchants/${m}/listings/integrations`;

export const PushView = z.object({
  id: z.string(), offerId: z.string(), listingName: z.string().nullish(), sku: z.string(), delta: z.number(),
  reason: z.enum(['sale', 'cancellation', 'return']), sourceRef: z.string(), state: z.string(), conflict: z.string().nullish(),
  detail: z.string().nullish(), attempts: z.number(), platformAfter: z.number().nullish(), applied: z.boolean(), createdAt: z.string(),
});
export type PushView = z.infer<typeof PushView>;
export const StockStatus = z.object({
  provider: CommerceProvider, connected: z.boolean(), locationId: z.string().nullish(), locationName: z.string().nullish(),
  canWrite: z.boolean(), pending: z.number(), done: z.number(), attention: z.array(PushView),
});
export type StockStatus = z.infer<typeof StockStatus>;
export const LocationView = z.object({ id: z.string(), name: z.string(), chosen: z.boolean() });
export type LocationView = z.infer<typeof LocationView>;

const stockKey = (m: string, p: string) => ['merchant', m, 'integrations', p, 'stock'] as const;
export const stockQuery = (m: string, p: CommerceProvider) => queryOptions({ queryKey: stockKey(m, p), queryFn: () => http(`${integrations(m)}/${p}/stock`, {}, StockStatus) });
export const locationsQuery = (m: string, p: CommerceProvider) => queryOptions({
  queryKey: ['merchant', m, 'integrations', p, 'locations'],
  queryFn: async () => (await http(`${integrations(m)}/${p}/locations`, {}, z.object({ items: z.array(LocationView) }))).items,
});

export function useChooseLocation(m: string, p: CommerceProvider) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (locationId: string) => http(`${integrations(m)}/${p}/location`, { method: 'PUT', body: { locationId } }, StockStatus),
    onSuccess: s => {
      qc.setQueryData(stockKey(m, p), s);
      void qc.invalidateQueries({ queryKey: ['merchant', m, 'integrations', p, 'locations'] });
      void qc.invalidateQueries({ queryKey: catalogueKeys.integrations(m) });
    },
  });
}

export function usePushAction(m: string, p: CommerceProvider) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'retry' | 'dismiss' }) => http(`${integrations(m)}/stock-changes/${encodeURIComponent(id)}/${action}`, { method: 'POST' }, PushView),
    onSuccess: () => void qc.invalidateQueries({ queryKey: stockKey(m, p) }),
  });
}

// ── partner access ──────────────────────────────────────────────────────────────────────────────────────────────────
export const Consent = z.object({
  partnerId: z.string(), displayName: z.string(), clientId: z.string(), scopes: z.array(z.string()),
  partnerState: z.enum(['active', 'disabled']), state: z.enum(['requested', 'approved', 'declined', 'revoked']),
  requestedAt: z.string(), decidedAt: z.string().nullish(),
});
export type Consent = z.infer<typeof Consent>;
const consentKey = (m: string) => ['merchant', m, 'settings', 'partners'] as const;
export const consentsQuery = (m: string) => queryOptions({
  queryKey: consentKey(m),
  queryFn: async () => (await http(`/api/v1/merchants/${m}/settings/partners`, {}, z.object({ items: z.array(Consent) }))).items,
});
export function useDecideConsent(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ partnerId, decision }: { partnerId: string; decision: 'approve' | 'decline' | 'revoke' }) =>
      http(`/api/v1/merchants/${m}/settings/partners/${encodeURIComponent(partnerId)}/decision`, { method: 'POST', body: { decision } }, Consent),
    onSuccess: c => qc.setQueryData<Consent[]>(consentKey(m), list => list?.map(x => (x.partnerId === c.partnerId ? c : x))),
  });
}
