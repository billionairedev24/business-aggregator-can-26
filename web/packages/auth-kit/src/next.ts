import { authOrigin } from './config';

/**
 * Only same-origin paths may be used as `next` (never `//host` or a URL). Control characters are refused anywhere: the
 * URL parser drops tabs and newlines, so `/\t/evil.example` would become `//evil.example` (S-20). Same rule as the
 * BFF's `NextRedirect.safe`.
 */
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;
export const safeNext = (next?: string | null) => (next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') && !CONTROL.test(next) ? next : undefined);

/**
 * Hand-off to the app's BFF after the JSON flow: the BFF starts authorization code + PKCE, the auth server answers at
 * once (its session exists), and the browser lands on `next` with a BFF session cookie.
 */
export function bffLoginUrl(next: string): string {
  return `/bff/login?next=${encodeURIComponent(next)}`;
}

/**
 * S-29: a mobile app (Northline, Northline Courier) opened northline-auth's authorization endpoint in the phone's
 * browser, which came here to sign in. The sign-in answer then names that request (`continueTo`) and the browser goes
 * back to it — the app gets its code — instead of the BFF hand-off. Only an authorization request on northline-auth
 * itself is followed.
 */
export function appAuthorizationUrl(continueTo: string | null | undefined): string | null {
  if (!continueTo) return null;
  try {
    const url = new URL(continueTo);
    return url.origin === new URL(authOrigin()).origin && url.pathname === '/oauth2/authorize' ? url.href : null;
  } catch {
    return null;
  }
}
