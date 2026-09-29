import { queryOptions, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from './http';

export const SessionUser = z.object({ id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string().nullish(), phone: z.string().nullish(), initials: z.string(), locale: z.string().nullish(), memberSince: z.string().nullish() });
export const Session = z.object({ user: SessionUser, acr: z.string().nullish() });
export type Session = z.infer<typeof Session>;

/** BFF session: 200 → signed in, 401 → signed out (null). */
export const sessionQuery = queryOptions({
  queryKey: ['session'],
  queryFn: async (): Promise<Session | null> => {
    try { return await http('/bff/session', {}, Session); } catch (e) { if (e instanceof ApiError && (e.status === 401 || e.status === 404)) return null; throw e; }
  },
  staleTime: 60_000,
});

export const useSession = () => useQuery(sessionQuery);

export function useSignOut() {
  const qc = useQueryClient();
  return async () => {
    try { await http('/bff/logout', { method: 'POST' }); } finally { qc.clear(); qc.setQueryData(sessionQuery.queryKey, null); window.location.assign('/sign-in'); }
  };
}
