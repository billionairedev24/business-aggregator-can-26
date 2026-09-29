/**
 * northline-auth (Spring Authorization Server) origin. The Studio drives its JSON sign-in / registration API directly
 * (same site, credentials included) so the auth server's own session exists when the BFF's authorization request
 * arrives — see docs/DECISIONS.md (Auth workstream). Configure with VITE_NL_AUTH_ORIGIN.
 */
export const AUTH_ORIGIN: string = (import.meta.env.VITE_NL_AUTH_ORIGIN as string | undefined) ?? 'http://localhost:9000';

export const authUrl = (path: string) => `${AUTH_ORIGIN}${path}`;

/** Ends the auth server's session, so "Not you? Sign out" can't silently sign the same person back in. */
export async function endAuthSession(): Promise<void> {
  await fetch(authUrl('/api/auth/sign-out'), { method: 'POST', credentials: 'include', headers: { accept: 'application/json' } });
}

/**
 * Hand-off to the studio BFF after the JSON flow: the BFF starts authorization code + PKCE, the auth server answers
 * at once (its session exists), and the browser lands on `next` with a BFF session cookie.
 */
export function bffLoginUrl(next: string): string {
  return `/bff/login?next=${encodeURIComponent(next)}`;
}
