import { configureAuthOrigin } from '@northline/auth-kit';

/**
 * northline-auth's public origin. The console drives its JSON sign-in API directly (same site, credentials included)
 * so the auth server's session exists when the console-bff's authorization request arrives (as the Studio does).
 * Resolved from the runtime `/config.js` (`window.__NL_CONFIG__.authOrigin`, written by the container from
 * NL_AUTH_ORIGIN), then the build-time VITE_NL_AUTH_ORIGIN, then the local default.
 */
const runtimeConfig = (globalThis as { __NL_CONFIG__?: { authOrigin?: string } }).__NL_CONFIG__;
export const AUTH_ORIGIN: string =
  runtimeConfig?.authOrigin || ((import.meta.env.VITE_NL_AUTH_ORIGIN as string | undefined) ?? 'http://localhost:9000');

configureAuthOrigin(AUTH_ORIGIN);

export { endAuthSession, bffLoginUrl, safeNext } from '@northline/auth-kit';
