import { redirect } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { sessionQuery } from '../../lib/session';
import type { AuthMode } from './SignedOutPage';

/** Search params of /sign-in and /register (all optional strings). */
export interface AuthSearch {
  next?: string;
  step?: string;
  identifier?: string;
  error?: string;
  recover?: string;
  firstName?: string;
  lastName?: string;
  email?: string;
}

const KEYS = ['next', 'step', 'identifier', 'error', 'recover', 'firstName', 'lastName', 'email'] as const;

export function validateAuthSearch(raw: Record<string, unknown>): AuthSearch {
  const out: AuthSearch = {};
  for (const k of KEYS) if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  return out;
}

/** Only same-origin paths may be used as `next` (never `//host` or a URL). */
export const safeNext = (next?: string) => (next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') ? next : undefined);

/** Already signed in → go where the user was heading. */
export async function redirectIfSignedIn(queryClient: QueryClient, search: AuthSearch) {
  const session = await queryClient.ensureQueryData(sessionQuery).catch(() => null);
  if (session) throw redirect({ href: safeNext(search.next) ?? '/' });
}

/** Tab switch keeps `next`. */
export const tabTarget = (mode: AuthMode, search: AuthSearch, recover?: boolean) => ({
  to: mode === 'register' ? '/register' : '/sign-in',
  search: { ...(search.next ? { next: search.next } : {}), ...(recover ? { recover: '1' } : {}) },
}) as const;
