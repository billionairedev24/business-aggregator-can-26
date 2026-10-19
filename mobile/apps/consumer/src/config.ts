/**
 * Runtime configuration, inlined by Metro from the build's EXPO_PUBLIC_* variables (app.config.ts documents them and
 * eas.json sets them per environment: development → dev, preview → staging, production → prod). The client id,
 * redirects and scopes are the `mobile-consumer` registration in northline-auth (S-29).
 */
const strip = (u: string) => u.replace(/\/+$/, '');

const siteOrigin = strip(process.env.EXPO_PUBLIC_SITE_ORIGIN ?? 'http://localhost:3000');

export const config = {
  apiUrl: strip(process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080/api/v1'),
  authIssuer: strip(process.env.EXPO_PUBLIC_AUTH_ISSUER ?? 'http://localhost:9000'),
  /** The consumer web: legal pages, the claimed https redirect, the passkey / Google / Apple sign-in page. */
  siteOrigin,
  /** The in-app fixture backend: the web smoke test and demos. Never in a production build (app.config.ts refuses). */
  fixtures: process.env.EXPO_PUBLIC_FIXTURES === '1',
  clientId: 'mobile-consumer',
  /** The system-browser sign-in's redirect (RFC 8252 § 7.1). */
  redirectUri: 'ca.northline.app:/oauth2redirect',
  /** 2026-10-04: where the identity provider sends the customer back after the ID check (the api's AGE_VERIFICATION_APP_RETURN_URL). */
  ageReturnUri: 'ca.northline.app:/age-verified',
  /** The in-app sign-in's redirect: the claimed https link on the consumer site (src/auth/handoff.ts). */
  httpsRedirectUri: `${siteOrigin}/app/oauth2redirect`,
  scopes: ['openid', 'profile', 'orders', 'bookings', 'offline_access'],
  /** The stock photos' variants (docs/runbooks/photos.md): a CDN, else the consumer web's /photos/. */
  photosBaseUrl: strip(process.env.EXPO_PUBLIC_PHOTOS_BASE_URL ?? `${siteOrigin}/photos`),
} as const;

/** The verbatim legal pages (design 09/10, S-63; English only, as on the web), opened outside the app. */
export const legalUrl = (doc: 'terms' | 'privacy') => `${config.siteOrigin}/legal/${doc}.html`;

/**
 * Redesign nav flags (as the web's VITE_NL_FULL_NAV / VITE_NL_TESTING_TOOLS; docs/design/redesign-spec.md § 3): unset,
 * the app hides what the demo hides — the Wallet & points and the dietary preferences rows of Account (§ 3.6) and the
 * pilot "Send feedback" button (§ 3.5). Their screens and every endpoint stay (a typed link still opens them). Read at
 * call time so a build sets them with EXPO_PUBLIC_FULL_NAV=1 / EXPO_PUBLIC_TESTING_TOOLS=1 (and tests per test).
 */
export const fullNav = () => process.env.EXPO_PUBLIC_FULL_NAV === '1';
export const testingTools = () => process.env.EXPO_PUBLIC_TESTING_TOOLS === '1';
