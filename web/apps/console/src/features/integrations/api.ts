import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** The scopes a key may carry (api `DeveloperRules.SCOPES`, the Studio's list). */
export const SCOPES = ['storefront:read', 'listings:read', 'listings:write', 'booking:read', 'booking:write', 'orders:read', 'payouts:read', 'reviews:read'] as const;

/** One business's API key (S-96; api `StaffAdmin.KeyRow`). The secret is never listed. */
export const Key = z.object({
  id: z.string(), merchantId: z.string(), businessName: z.string().nullish(), name: z.string(), scopes: z.array(z.string()), prefix: z.string(),
  rateLimit: z.number(), createdAt: z.string(), lastUsedAt: z.string().nullish(), revokedAt: z.string().nullish(),
});
export type Key = z.infer<typeof Key>;

export const keysQuery = queryOptions({
  queryKey: ['console', 'api-keys'],
  queryFn: () => http('/api/v1/console/api-keys', {}, z.object({ items: z.array(Key) })).then(r => r.items),
});

function useKeyMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'api-keys'] }) });
}
export const useIssueKey = () => useKeyMutation((v: { merchantId: string; name: string; scopes: string[] }) =>
  http('/api/v1/console/api-keys', { method: 'POST', body: v }, z.object({ key: Key, secret: z.string() })));
export const useRevokeKey = () => useKeyMutation((id: string) => http(`/api/v1/console/api-keys/${encodeURIComponent(id)}/revoke`, { method: 'POST' }, Key));
