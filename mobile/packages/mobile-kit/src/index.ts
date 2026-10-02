/**
 * @northline/mobile-kit — what every Northline native app shares (S-87 built it for the courier app; the consumer
 * app, S-97, reuses it): DPoP-bound OAuth with PKCE, the api client (signed or, for public reads, anonymous), secure
 * storage, the theme from `@northline/tokens`, en/fr-CA i18n and the push-notification hook point (S-102).
 */
export { ApiClient, DEFAULT_TIMEOUT_MS, type ApiAnswer, type CallAuth, type RequestOptions } from './api/client';
export { ApiError, NetworkError, SignedOutError, apiErrorOf, retryAfterSeconds, type FieldError } from './api/errors';
export { DpopSession, OAuthError, type OAuthConfig, type PendingSignIn } from './auth/session';
export { codeChallenge, codeVerifier } from './auth/pkce';
export { AppSignIn } from './auth/handoff';
export {
  SoftwareDeviceKey,
  createDeviceKey,
  deleteDeviceKey,
  loadDeviceKey,
  thumbprintOf,
  type DeviceKey,
  type PublicJwk,
} from './dpop/key';
export { ServerClock, accessTokenHash, createProof, htu, type ProofInput } from './dpop/proof';
export { randomBytes, randomId, randomToken } from './dpop/random';
export { deviceSecureStorage, memorySecureStorage, type SecureStorage } from './storage/secure';
export { base64, base64url, fromBase64url, fromUtf8, utf8 } from './bytes';
export {
  LOCALES,
  createI18n,
  deviceLocale,
  formatMessage,
  pluralCategory,
  resolveLocale,
  type I18n,
  type Locale,
  type Params,
} from './i18n';
export { MIN_TARGET, colors, fonts, radius, space, theme, type Theme } from './theme';
export { PushHooks, setPushRegistrar, type PushRegistrar } from './push/hooks';
