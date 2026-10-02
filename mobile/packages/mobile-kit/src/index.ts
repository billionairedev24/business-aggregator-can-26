/**
 * @northline/mobile-kit — what every Northline native app shares (S-87 builds it for the courier app; the consumer
 * app reuses it in phase 4): DPoP-bound OAuth with PKCE, the api client, secure storage, the theme from
 * `@northline/tokens` and en/fr-CA i18n.
 */
export { ApiClient, type ApiAnswer, type RequestOptions } from './api/client';
export { ApiError, NetworkError, SignedOutError, apiErrorOf, retryAfterSeconds, type FieldError } from './api/errors';
// S-102: push registration, notification taps and deep links
export {
  COURIER_SCHEME,
  CONSUMER_SCHEME,
  PushRegistration,
  expoPushPlatform,
  pushRegistrar,
  handleNotificationTaps,
  parseDeepLink,
  routeOf,
  type DeepLink,
  type DeviceRegistryApi,
  type ExpoNotificationsModule,
  type PushPermission,
  type PushPlatform,
  type PushRegistrationOptions,
} from './push';
export { DpopSession, OAuthError, type OAuthConfig, type PendingSignIn } from './auth/session';
export { codeChallenge, codeVerifier } from './auth/pkce';
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
