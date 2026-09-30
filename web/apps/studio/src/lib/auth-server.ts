/**
 * northline-auth (Spring Authorization Server) origin. The Studio drives its JSON sign-in / registration API directly
 * (same site, credentials included) so the auth server's own session exists when the BFF's authorization request
 * arrives — see docs/DECISIONS.md (Auth workstream).
 *
 * Resolved in this order: the runtime configuration `/config.js` (`window.__NL_CONFIG__.authOrigin`, written by the
 * Studio container from NL_AUTH_ORIGIN at start-up — S-14, so one image serves every environment), then the build-time
 * VITE_NL_AUTH_ORIGIN, then the local default.
 */
const runtimeConfig = (globalThis as { __NL_CONFIG__?: { authOrigin?: string } }).__NL_CONFIG__;
export const AUTH_ORIGIN: string =
  runtimeConfig?.authOrigin || ((import.meta.env.VITE_NL_AUTH_ORIGIN as string | undefined) ?? 'http://localhost:9000');

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

/**
 * S-29: a mobile app (Northline, Northline Courier) opened northline-auth's authorization endpoint in the phone's
 * browser, which came here to sign in. The sign-in answer then names that request (`continueTo`) and the browser goes
 * back to it — the app gets its code — instead of the Studio's BFF hand-off. Only an authorization request on
 * northline-auth itself is followed.
 */
export function appAuthorizationUrl(continueTo: string | null | undefined): string | null {
  if (!continueTo) return null;
  try {
    const url = new URL(continueTo);
    return url.origin === new URL(AUTH_ORIGIN).origin && url.pathname === '/oauth2/authorize' ? url.href : null;
  } catch {
    return null;
  }
}
