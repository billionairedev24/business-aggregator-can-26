/**
 * northline-auth's public origin. Each app sets it once at start-up from its own runtime configuration (the Studio:
 * `/config.js` or VITE_NL_AUTH_ORIGIN; the consumer app: NL_AUTH_ORIGIN through the server-rendered page). The apps drive
 * the JSON sign-in / registration API there directly (same site, credentials included) so the auth server's own
 * session exists when their BFF's authorization request arrives.
 */
let origin = 'http://localhost:9000';

export function configureAuthOrigin(value: string | undefined | null) {
  if (value) origin = value.replace(/\/$/, '');
}

export const authOrigin = () => origin;
export const authUrl = (path: string) => `${origin}${path}`;

/** Ends the auth server's session, so "Not you? Sign out" can't silently sign the same person back in. */
export async function endAuthSession(): Promise<void> {
  await fetch(authUrl('/api/auth/sign-out'), { method: 'POST', credentials: 'include', headers: { accept: 'application/json' } });
}
