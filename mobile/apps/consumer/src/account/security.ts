import { ApiError, apiErrorOf, NetworkError } from '@northline/mobile-kit';

import type { ActiveSession, Security } from '../api/account';
import { config } from '../config';
import { services } from '../services';

/**
 * northline-auth's security API (S-19, `/api/auth/security`; the consumer site's Security & sign-in uses the same):
 * passkeys, the authenticator app, active sessions, signing a session out and signing out everywhere else. It works
 * in the **auth session** — the platform cookie store the in-app sign-in left (S-98), as the step-up does (S-99) — and
 * needs a second factor in it: `null` = no such session on this phone (or no recent second factor), the person
 * confirms with their authenticator code first. A phone signed in in the system browser has no auth session here:
 * the screen sends it to the website.
 */
async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response;
  try {
    res = await services().fetch(`${config.authIssuer}${path}`, {
      ...init,
      credentials: 'include',
      headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.headers as Record<string, string> | undefined) },
    });
  } catch (e) {
    throw new NetworkError(e);
  }
  if (!res.ok) throw await apiErrorOf(res);
  return (await res.json().catch(() => ({}))) as T;
}

export const securityApi = {
  /** `null` when the auth server asks for a second factor first (401). */
  async overview(): Promise<Security | null> {
    try {
      const s = await call<Security>('/api/auth/security');
      return { ...s, passkeys: s.passkeys ?? [], sessions: s.sessions ?? [] };
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return null;
      throw e;
    }
  },
  revoke: (sessionId: string) =>
    call<{ sessions: ActiveSession[] }>(`/api/auth/security/sessions/${encodeURIComponent(sessionId)}/revoke`, { method: 'POST', body: JSON.stringify({ current: null }) }),
  revokeOthers: () => call<{ revoked: number }>('/api/auth/security/sessions/revoke-others', { method: 'POST', body: JSON.stringify({ current: null }) }),
};
