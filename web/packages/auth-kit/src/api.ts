import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import { authUrl } from './config';
import type { AuthKitT } from './messages';

/** The signed-in person as northline-auth (and the BFFs' `/bff/session`) describe them. */
export const AuthUser = z.object({ id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string().nullish(), phone: z.string().nullish(), initials: z.string(), locale: z.string().nullish(), memberSince: z.string().nullish() });

// ── validation (docs/spec/validation-rules.md § Registration — same rules and messages as the auth server) ──────────

export const PHONE_PATTERN = /^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

/**
 * `termsLanguage` / `termsEnglishRequested` (S-116): the language the Terms were shown in, and an express request for the
 * English version where they are presented in French first — recorded with the acceptance.
 */
export interface RegisterValues { firstName: string; lastName: string; phone: string; email: string; terms: boolean; termsLanguage?: 'en' | 'fr'; termsEnglishRequested?: boolean }
export const REGISTER_FIELDS = ['firstName', 'lastName', 'phone', 'email', 'terms'] as const;

export const registerSchema = (t: AuthKitT) => z.object({
  firstName: z.string().trim().min(1, t('firstNameRequired')),
  lastName: z.string().trim().min(1, t('lastNameRequired')),
  phone: z.string().trim().min(1, t('phoneRequired')).regex(PHONE_PATTERN, t('phoneFormat')),
  email: z.string().trim().min(1, t('emailRequired')).regex(EMAIL_PATTERN, t('emailFormat')),
  terms: z.boolean().refine(v => v, t('termsRequired')),
});

/** A 6-digit code (phone verification, authenticator app). */
export const codeSchema = (t: AuthKitT) => z.string().trim().min(1, t('codeRequired')).regex(/^\d{6}$/, t('codeFormat'));
export const backupCodeSchema = (t: AuthKitT) => z.string().trim().min(1, t('backupRequired'));

/** First message of a zod result, or undefined. */
export function firstIssue(schema: z.ZodType, value: unknown): string | undefined {
  const r = schema.safeParse(value);
  return r.success ? undefined : r.error.issues[0]?.message;
}

// ── responses ─────────────────────────────────────────────────────────────────────────────────────────────────────

export const RegistrationStep = z.object({ step: z.enum(['otp', 'mfa']), phone: z.string(), resendAfterSeconds: z.number(), channel: z.enum(['sms', 'voice']) });
export type RegistrationStep = z.infer<typeof RegistrationStep>;
export const SignInStarted = z.object({ identifier: z.string(), factors: z.array(z.string()) });
/** `continueTo` (S-29): a mobile app's authorization request that sent the browser here, to go back to. */
export const AuthSession = z.object({ user: AuthUser, acr: z.string().nullish(), continueTo: z.string().nullish() });
export type AuthSession = z.infer<typeof AuthSession>;
/** S-62: a sign-in code is on its way (the same answer whether or not an account matched). */
export const CodeSent = z.object({ resendAfterSeconds: z.number(), channel: z.enum(['sms', 'voice']) });
export type CodeSent = z.infer<typeof CodeSent>;
export const TotpSetup = z.object({ secret: z.string(), otpauthUri: z.string(), qrCode: z.string() });
export type TotpSetup = z.infer<typeof TotpSetup>;
/** WebAuthn options exactly as Spring Security serialises them (base64url bytes). */
export const PasskeyOptions = z.record(z.string(), z.unknown());

const post = <T>(path: string, schema: z.ZodType<T>, body: unknown = {}, headers?: Record<string, string>) => http(authUrl(path), { method: 'POST', body, headers }, schema);
/** The UI language, so the SMS / voice code is worded in it (S-8). */
const language = (locale: string) => ({ 'accept-language': locale === 'fr' ? 'fr-CA' : 'en-CA' });

// ── northline-auth JSON API ───────────────────────────────────────────────────────────────────────────────────────

export const authApi = {
  register: (v: RegisterValues, locale = 'en') => post('/api/auth/register', RegistrationStep, { ...v, firstName: v.firstName.trim(), lastName: v.lastName.trim(), phone: v.phone.trim(), email: v.email.trim() }, language(locale)),
  resend: (channel: 'sms' | 'voice', locale = 'en') => post('/api/auth/register/resend', RegistrationStep, { channel }, language(locale)),
  verifyPhone: (code: string) => post('/api/auth/register/verify', RegistrationStep, { code: code.trim() }),
  passkeyRegistrationOptions: () => post('/api/auth/register/passkey/options', PasskeyOptions),
  registerPasskey: (credential: unknown) => post('/api/auth/register/passkey', AuthSession, { credential, label: 'Passkey' }),
  totpSetup: () => post('/api/auth/register/totp', TotpSetup),
  registerTotp: (code: string) => post('/api/auth/register/totp/verify', AuthSession, { code: code.trim() }),
  startSignIn: (identifier: string) => post('/api/auth/sign-in', SignInStarted, { identifier: identifier.trim() }),
  passkeySignInOptions: () => post('/api/auth/sign-in/passkey/options', PasskeyOptions),
  signInPasskey: (credential: unknown) => post('/api/auth/sign-in/passkey', AuthSession, { credential }),
  signInTotp: (code: string) => post('/api/auth/sign-in/totp', AuthSession, { code: code.trim() }),
  signInBackupCode: (code: string) => post('/api/auth/sign-in/backup-code', AuthSession, { code: code.trim() }),
  // S-62, the consumer site: a code to the phone instead of a second factor, and registration without one.
  sendSignInCode: (channel: 'sms' | 'voice' = 'sms', locale = 'en') => post('/api/auth/sign-in/code', CodeSent, { channel }, language(locale)),
  signInWithCode: (code: string) => post('/api/auth/sign-in/code/verify', AuthSession, { code: code.trim() }),
  completeWithoutSecondFactor: () => post('/api/auth/register/complete', AuthSession),
};

export const useAuthMutation = <A, R>(fn: (a: A) => Promise<R>) => useMutation({ mutationFn: fn });

/**
 * Google / Apple via the auth server (OIDC federation); it returns to the app's /sign-in or /register — the consumer
 * site passes `app: 'consumer'` so it comes back there (S-62).
 */
export const federationUrl = (provider: 'google' | 'apple', app?: 'consumer') =>
  authUrl(`/oauth2/authorization/${provider}${app ? `?app=${app}` : ''}`);
