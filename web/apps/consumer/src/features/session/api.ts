import { queryOptions, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import { endAuthSession } from '@northline/auth-kit';

/**
 * The consumer-bff session (docs/CONSUMER_WEB_PLAN.md § Session): `GET /bff/session` answers 200 for everyone —
 * `user` is null for a guest; `guestId` keys what a guest owns (the cart) and survives signing in; `location.city` is
 * the CDN's guess from the IP. `acr` is "mfa" only after a second factor (consumers don't need one).
 */
export const SessionUser = z.object({
  id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string().nullish(), phone: z.string().nullish(),
  initials: z.string(), locale: z.string().nullish(), memberSince: z.string().nullish(),
});
export type SessionUser = z.infer<typeof SessionUser>;
export const Session = z.object({
  user: SessionUser.nullish(),
  acr: z.string().nullish(),
  sid: z.string().nullish(),
  guestId: z.string().nullish(),
  location: z.object({ city: z.string() }).nullish(),
});
export type Session = z.infer<typeof Session>;

const GUEST: Session = { user: null };

/** Loaded in the browser only (never during server rendering: SSR pages are the same for everyone). */
export const sessionQuery = queryOptions({
  queryKey: ['session'],
  queryFn: async (): Promise<Session> => {
    try { return await http('/bff/session', {}, Session); } catch (e) { if (e instanceof ApiError && (e.status === 401 || e.status === 404)) return GUEST; throw e; }
  },
  staleTime: 60_000,
});

export const useSession = () => useQuery(sessionQuery);

/** Signed in, a guest, or not known yet (still loading, or the session couldn't be read: treated as a guest). */
export function useViewer() {
  const q = useSession();
  return { user: q.data?.user ?? null, loading: q.isPending, session: q.data ?? null };
}

/** The sign-in page, coming back to `next` (a local path) afterwards. */
export const signInHref = (next?: string, mode: 'sign-in' | 'register' = 'sign-in') =>
  `/${mode}${next && next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`;

/**
 * Sign out: ends the BFF session (`POST /bff/logout` → 204, revokes the tokens) and northline-auth's own session, so
 * "Not you?" can't silently sign the same person back in; then reloads the page as a guest. Either call failing still
 * signs out locally.
 */
export function useSignOut() {
  const qc = useQueryClient();
  return async (then = '/') => {
    await Promise.allSettled([http('/bff/logout', { method: 'POST' }), endAuthSession()]);
    qc.clear();
    window.location.assign(then);
  };
}
