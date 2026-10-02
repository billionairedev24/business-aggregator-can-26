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
  /** The in-app sign-in's redirect: the claimed https link on the consumer site (src/auth/handoff.ts). */
  httpsRedirectUri: `${siteOrigin}/app/oauth2redirect`,
  scopes: ['openid', 'profile', 'orders', 'bookings', 'offline_access'],
} as const;

/** The verbatim legal pages (design 09/10, S-63; English only, as on the web), opened outside the app. */
export const legalUrl = (doc: 'terms' | 'privacy') => `${config.siteOrigin}/legal/${doc}.html`;
