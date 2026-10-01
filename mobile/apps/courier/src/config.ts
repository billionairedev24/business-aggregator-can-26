/**
 * Runtime configuration, inlined by Metro from the build's EXPO_PUBLIC_* variables (app.config.ts documents them).
 * The client id, redirect and scopes are the `courier-app` registration in northline-auth (S-29).
 */
export const config = {
  apiUrl: (process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080/api/v1').replace(/\/+$/, ''),
  authIssuer: (process.env.EXPO_PUBLIC_AUTH_ISSUER ?? 'http://localhost:9000').replace(/\/+$/, ''),
  /** The in-app fixture backend: the web smoke test and demos. Never in a production build (app.config.ts refuses). */
  fixtures: process.env.EXPO_PUBLIC_FIXTURES === '1',
  clientId: 'courier-app',
  redirectUri: 'ca.northline.courier:/oauth2redirect',
  scopes: ['openid', 'courier', 'deliveries'],
} as const;

/** Location pings while a run is open: every 4 s (S-88: customers see moves ≤ 5 s apart; the server allows one per 2 s). */
export const PING_INTERVAL_MS = 4000;
/** How often the open run is read again while the app is in the foreground. */
export const RUN_REFRESH_MS = 30_000;
