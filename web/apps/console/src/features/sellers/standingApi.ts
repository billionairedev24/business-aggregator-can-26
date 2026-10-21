import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** S-156: a business's standing on its seller detail (server: ca.northline.standing, console endpoints). */
const BASE = '/api/v1/console/standing';

const Consequence = z.object({
  id: z.string(), kind: z.string(), cause: z.string(), reason: z.string(), startedAt: z.string(), endsAt: z.string().nullish(), active: z.boolean(),
  liftedAt: z.string().nullish(), liftReason: z.string().nullish(), liftedByStaff: z.boolean(),
  appeal: z.object({ number: z.string(), state: z.string() }).nullish(),
});
export type StandingConsequence = z.infer<typeof Consequence>;
const Coaching = z.object({
  id: z.string(), title: z.string(), note: z.string().nullish(), dueOn: z.string().nullish(), state: z.string(), createdAt: z.string(),
  tasks: z.array(z.object({ id: z.string(), title: z.string(), done: z.boolean() })),
});
export type StandingCoaching = z.infer<typeof Coaching>;
export const SellerStanding = z.object({
  active: z.array(Consequence), past: z.array(Consequence), coaching: Coaching.nullish(),
  appeals: z.array(z.object({ id: z.string(), number: z.string(), state: z.string() })),
});
export type SellerStanding = z.infer<typeof SellerStanding>;

export const sellerStandingQuery = (businessId: string) => queryOptions({
  queryKey: ['console', 'standing', businessId],
  queryFn: () => http(`${BASE}/${encodeURIComponent(businessId)}`, {}, SellerStanding),
});

function useStandingMutation<V, R>(businessId: string, fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console'] }) });
}
export const useLift = (businessId: string) => useStandingMutation(businessId, (v: { id: string; reason: string }) =>
  http(`${BASE}/consequences/${encodeURIComponent(v.id)}/lift`, { method: 'POST', body: { reason: v.reason } }));
export const useStartCoaching = (businessId: string) => useStandingMutation(businessId, (v: { title: string; note?: string; dueOn?: string; tasks: string[] }) =>
  http(`${BASE}/${encodeURIComponent(businessId)}/coaching`, { method: 'POST', body: v }));
export const useCloseCoaching = (businessId: string) => useStandingMutation(businessId, (v: { planId: string; completed: boolean }) =>
  http(`${BASE}/coaching/${encodeURIComponent(v.planId)}/close`, { method: 'POST', body: { completed: v.completed } }));
