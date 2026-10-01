import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import { Member } from '../team/api';

export const Shift = z.object({ id: z.string(), userId: z.string(), name: z.string(), startsAt: z.string(), endsAt: z.string(), duty: z.string() });
export type Shift = z.infer<typeof Shift>;
/** S-96 (api `StaffAdmin.Rota`): shifts from 12 h ago to a week ahead, who is on now, and the staff to pick from. */
export const Rota = z.object({ asOf: z.string(), shifts: z.array(Shift), now: z.array(Shift), staff: z.array(Member) });
export type Rota = z.infer<typeof Rota>;

export const rotaQuery = queryOptions({ queryKey: ['console', 'oncall'], queryFn: () => http('/api/v1/console/oncall', {}, Rota) });

function useRotaMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'oncall'] }) });
}
export const useAddShift = () => useRotaMutation((v: { userId: string; startsAt: string; endsAt: string; duty: string }) =>
  http('/api/v1/console/oncall/shifts', { method: 'POST', body: v }, Shift));
export const useHandOver = () => useRotaMutation((v: { id: string; userId: string }) =>
  http(`/api/v1/console/oncall/shifts/${encodeURIComponent(v.id)}/hand-over`, { method: 'POST', body: { userId: v.userId } }, Shift));
export const useRemoveShift = () => useRotaMutation((id: string) => http(`/api/v1/console/oncall/shifts/${encodeURIComponent(id)}`, { method: 'DELETE' }));
