import { queryOptions, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from './http';
import { endAuthSession } from './auth-server';

export const SessionUser = z.object({ id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string().nullish(), phone: z.string().nullish(), initials: z.string(), locale: z.string().nullish(), memberSince: z.string().nullish() });
/** `devAuth` is set only by the Vite dev server's NL_DEV_USER mode (no auth-server session exists then). */
export const Session = z.object({ user: SessionUser, acr: z.string().nullish(), devAuth: z.boolean().nullish() });
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

/**
 * Sign out (account menu, onboarding "Not you? Sign out"): ends the BFF session (`POST /bff/logout` → 204, revokes the
 * tokens) and the auth server's session, then shows the sign-in page. Either call failing still signs out locally.
 */
export function useSignOut() {
  const qc = useQueryClient();
  return async () => {
    await Promise.allSettled([http('/bff/logout', { method: 'POST' }), endAuthSession()]);
    qc.clear();
    qc.setQueryData(sessionQuery.queryKey, null);
    window.location.assign('/sign-in');
  };
}
