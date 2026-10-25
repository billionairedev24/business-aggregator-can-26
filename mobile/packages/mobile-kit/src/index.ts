/**
 * @northline/mobile-kit — what every Northline native app shares (S-87 built it for the courier app; the consumer
 * app, S-97, reuses it): DPoP-bound OAuth with PKCE, the api client (signed or, for public reads, anonymous), secure
 * storage, the theme from `@northline/tokens`, en/fr-CA i18n and the push-notification hook point (S-102).
 */
export { ApiClient, DEFAULT_TIMEOUT_MS, type ApiAnswer, type CallAuth, type RequestOptions } from './api/client';
export { ApiError, NetworkError, SignedOutError, apiErrorOf, retryAfterSeconds, type FieldError } from './api/errors';
// S-102: push registration, notification taps and deep links
export {
  COURIER_SCHEME,
  CONSUMER_SCHEME,
  PushRegistration,
  expoPushPlatform,
  pushRegistrar,
  handleNotificationTaps,
  installPush,
  siteHostOf,
  parseDeepLink,
  routeOf,
  type AppPush,
  type AppPushOptions,
  type DeepLink,
  type DeviceRegistryApi,
  type ExpoNotificationsModule,
  type PushPermission,
  type PushPlatform,
  type PushRegistrationOptions,
} from './push';
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
export { MIN_TARGET, brand, colors, fontAssets, fontSource, fonts, radius, ramp, semantic, shadow, space, text, theme, type Theme } from './theme';
// redesign phase 4: the palette's mark + wordmark and the Phosphor icons (regular / fill)
export { Logo, LogoMark } from './ui/Logo';
export { ICON_PATHS, Icon, type IconName } from './ui/Icon';
// light direction: the illustrated scenes that stand in for photographs
export { Art, TONES, subjectFor, toneFor, type ArtProps, type Subject, type Tone } from './ui/Art';
// real photos (2026-10-19): the photo library's stock photos, the drawing only while a slot has none
export { Photo, Scene, sourceFor, type PhotoProps } from './ui/Photo';
export { categorySlot, configurePhotoBase, cuisineSlot, photoBase, photoFor, slugSlot, type Crop, type ResolvedPhoto } from './photos/library';
export { PushHooks, setPushRegistrar, type PushRegistrar } from './push/hooks';
// S-169: the component kit on tokens v2 (Plainsight), shared by the consumer and courier apps
export * from './ui/kit';
// mobile gaps part 1: photos from the apps (pilot feedback screenshots, problem-report photos) and pilot feedback
export {
  REPORT_PHOTO_MAX,
  REPORT_PHOTO_MAX_BYTES,
  REPORT_PHOTO_TYPES,
  SCREENSHOT_MAX_BYTES,
  SCREENSHOT_TYPES,
  checkPhoto,
  photoForm,
  photoType,
  pilotApi,
  screenOf,
  type FeedbackApp,
  type FeedbackCategory,
  type FeedbackSeverity,
  type PhotoProblem,
  type PickedImage,
  type PilotFeedback,
  type PilotStatus,
} from './photos';
