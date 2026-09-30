/**
 * @northline/auth-kit (S-62): what the Studio's and the consumer site's sign-in / registration pages share — the
 * northline-auth JSON API client and its zod schemas (validation-rules.md), error mapping, passkeys (WebAuthn),
 * countdowns and rate-limit notices, the Google / Apple / passkey marks, `next` safety and the BFF hand-off, and the
 * shared copy. Each app keeps its own screens and page copy.
 */
export * from './api';
export * from './config';
export * from './countdown';
export * from './errors';
export * from './marks';
export * from './messages';
export * from './next';
export * from './RateLimit';
export * from './webauthn';
