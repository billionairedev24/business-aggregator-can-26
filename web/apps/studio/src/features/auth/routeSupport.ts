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
  /** S-18: back from Google/Apple — which one, linking an existing account (`link`), Apple private relay (`relay=1`). */
  provider?: string;
  link?: string;
  relay?: string;
}

/** The Google / Apple context of the signed-out page, from `?provider=` / `?link=` / `?relay=` (S-18). */
export interface FederationContext { provider: 'google' | 'apple'; linking: boolean; relay: boolean }
export function federationContext(search: AuthSearch): FederationContext | undefined {
  const provider = search.link ?? search.provider;
  if (provider !== 'google' && provider !== 'apple') return undefined;
  return { provider, linking: !!search.link, relay: search.relay === '1' };
}

const KEYS = ['next', 'step', 'identifier', 'error', 'recover', 'firstName', 'lastName', 'email', 'provider', 'link', 'relay'] as const;

export function validateAuthSearch(raw: Record<string, unknown>): AuthSearch {
  const out: AuthSearch = {};
  for (const k of KEYS) if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  return out;
}

/**
 * Only same-origin paths may be used as `next` (never `//host` or a URL). Control characters are refused anywhere: the
 * URL parser drops tabs and newlines, so `/\t/evil.example` would become `//evil.example` (S-20). Same rule as the
 * BFF's `NextRedirect.safe`.
 */
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;
export const safeNext = (next?: string) => (next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') && !CONTROL.test(next) ? next : undefined);

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
