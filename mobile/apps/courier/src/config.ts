/**
 * Runtime configuration, inlined by Metro from the build's EXPO_PUBLIC_* variables (app.config.ts documents them).
 * The client id, redirect and scopes are the `courier-app` registration in northline-auth (S-29).
 */
const siteOrigin = (process.env.EXPO_PUBLIC_SITE_ORIGIN ?? 'http://localhost:3000').replace(/\/+$/, '');

export const config = {
  apiUrl: (process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080/api/v1').replace(/\/+$/, ''),
  authIssuer: (process.env.EXPO_PUBLIC_AUTH_ISSUER ?? 'http://localhost:9000').replace(/\/+$/, ''),
  /** The in-app fixture backend: the web smoke test and demos. Never in a production build (app.config.ts refuses). */
  fixtures: process.env.EXPO_PUBLIC_FIXTURES === '1',
  clientId: 'courier-app',
  redirectUri: 'ca.northline.courier:/oauth2redirect',
  /** S-159: the consumer web (the claimed https redirect of the in-app sign-in). */
  siteOrigin,
  /** S-159: the in-app sign-in's redirect — the claimed https link on the consumer site (mobile-kit's AppSignIn). */
  httpsRedirectUri: `${siteOrigin}/courier/oauth2redirect`,
  scopes: ['openid', 'courier', 'deliveries'],
  /** The stock photos' variants (docs/runbooks/photos.md): a CDN or the consumer web's /photos/. */
  photosBaseUrl: (process.env.EXPO_PUBLIC_PHOTOS_BASE_URL ?? 'http://localhost:3000/photos').replace(/\/+$/, ''),
} as const;

/** Location pings while a run is open: every 4 s (S-88: customers see moves ≤ 5 s apart; the server allows one per 2 s). */
export const PING_INTERVAL_MS = 4000;
/** How often the open run is read again while the app is in the foreground. */
export const RUN_REFRESH_MS = 30_000;

/** Redesign § 3.5: the pilot's "Feedback" control is a testing tool — only builds with EXPO_PUBLIC_TESTING_TOOLS=1 show it. */
export const testingTools = () => process.env.EXPO_PUBLIC_TESTING_TOOLS === '1';

/** S-169: dev-only screens (the kit's gallery at /dev/gallery) — a development build (Metro, `__DEV__`) or the fixture build, never a store build. */
export const devTools = () => (typeof __DEV__ !== 'undefined' && __DEV__) || config.fixtures;
