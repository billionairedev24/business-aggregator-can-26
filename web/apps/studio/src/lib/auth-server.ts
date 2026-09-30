import { configureAuthOrigin } from '@northline/auth-kit';

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

configureAuthOrigin(AUTH_ORIGIN);

// The helpers live in @northline/auth-kit since S-62 (shared with the consumer site).
export { authUrl, endAuthSession, bffLoginUrl, appAuthorizationUrl } from '@northline/auth-kit';
