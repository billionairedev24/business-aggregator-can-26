import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** The console roles an admin grants (api `StaffRole`). */
export const ROLES = ['admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst', 'privacy', 'merchant_success'] as const;

/** S-96 (api `StaffAdmin`). */
export const Member = z.object({ id: z.string(), name: z.string(), email: z.string().nullish(), roles: z.array(z.string()), since: z.string().nullish() });
export type Member = z.infer<typeof Member>;
export const Team = z.object({
  roles: z.array(z.object({ role: z.string(), people: z.number(), screens: z.array(z.string()), actions: z.array(z.string()) })),
  members: z.array(Member),
});
export type Team = z.infer<typeof Team>;
export const AuditRow = z.object({
  id: z.string(), at: z.string(), actorId: z.string().nullish(), actorName: z.string().nullish(), role: z.string().nullish(), action: z.string(),
  targetType: z.string().nullish(), targetId: z.string().nullish(), merchantId: z.string().nullish(), businessName: z.string().nullish(),
  before: z.record(z.string(), z.unknown()).nullish(), after: z.record(z.string(), z.unknown()).nullish(),
});
export type AuditRow = z.infer<typeof AuditRow>;
export const AuditPage = z.object({ items: z.array(AuditRow), next: z.string().nullish() });
export type AuditPage = z.infer<typeof AuditPage>;

export interface AuditFilter { actor?: string; action?: string; business?: string; from?: string; to?: string }

export const teamQuery = queryOptions({ queryKey: ['console', 'team'], queryFn: () => http('/api/v1/console/team', {}, Team) });

/** `mine` = GET /api/v1/console/me/audit (my audit trail, every staff member). */
export const auditQuery = (filter: AuditFilter, before: string | undefined, mine = false) => queryOptions({
  queryKey: ['console', 'audit', mine, filter, before ?? null],
  queryFn: () => {
    const q = new URLSearchParams();
    if (!mine) for (const [k, v] of Object.entries(filter)) if (v) q.set(k, v);
    if (before) q.set('before', before);
    const s = q.toString();
    return http(`/api/v1/console/${mine ? 'me/audit' : 'audit'}${s ? `?${s}` : ''}`, {}, AuditPage);
  },
  placeholderData: keepPreviousData,
});

function useTeamMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => { void qc.invalidateQueries({ queryKey: ['console', 'team'] }); void qc.invalidateQueries({ queryKey: ['console', 'audit'] }); } });
}
const enc = encodeURIComponent;
export const useInvite = () => useTeamMutation((v: { email: string; role: string }) => http('/api/v1/console/team/invite', { method: 'POST', body: v }, Member));
export const useGrantRole = () => useTeamMutation((v: { userId: string; role: string }) => http(`/api/v1/console/team/${enc(v.userId)}/roles`, { method: 'POST', body: { role: v.role } }, Member));
export const useRevokeRole = () => useTeamMutation((v: { userId: string; role: string }) => http(`/api/v1/console/team/${enc(v.userId)}/roles/${enc(v.role)}`, { method: 'DELETE' }, Member));
