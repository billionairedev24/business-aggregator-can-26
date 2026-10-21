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
/** S-157: a ticked marketing checkbox (CASL express consent) — the category and the wording version the form showed. */
export interface MarketingConsent { category: 'marketing_email' | 'marketing_sms'; wordingVersion: string }
export interface RegisterValues {
  firstName: string; lastName: string; phone: string; email: string; terms: boolean; termsLanguage?: 'en' | 'fr'; termsEnglishRequested?: boolean;
  /** S-157: the ticked boxes, and where (the app's sign-up: `app_signup`; default `web_signup`) */
  marketing?: MarketingConsent[]; consentSource?: 'web_signup' | 'app_signup';
}
export const REGISTER_FIELDS = ['firstName', 'lastName', 'phone', 'email', 'terms'] as const;

export const registerSchema = (t: AuthKitT) => z.object({
  firstName: z.string().trim().min(1, t('firstNameRequired')),
  lastName: z.string().trim().min(1, t('lastNameRequired')),
  phone: z.string().trim().min(1, t('phoneRequired')).regex(PHONE_PATTERN, t('phoneFormat')),
  email: z.string().trim().min(1, t('emailRequired')).regex(EMAIL_PATTERN, t('emailFormat')),
  terms: z.boolean().refine(v => v, t('termsRequired')),
});

// ── S-154: email + password accounts (NIST 800-63B: 12–128 characters, no composition rules; the server also refuses
// common / breached passwords and the email address) ───────────────────────────────────────────────────────────────

export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 128;
/** Length in characters as a person counts them (code points, after NFKC) — the server's rule. */
export const passwordLength = (p: string) => [...p.normalize('NFKC')].length;
export const passwordSchema = (t: AuthKitT) => z.string()
  .refine(p => p.length > 0, t('passwordRequired'))
  .refine(p => p.length === 0 || passwordLength(p) >= PASSWORD_MIN, t('passwordTooShort'))
  .refine(p => passwordLength(p) <= PASSWORD_MAX, t('passwordTooLong'));

export type AccountApp = 'studio' | 'consumer' | 'console';
export interface CreateAccountValues {
  firstName: string; lastName: string; email: string; password: string; passwordConfirm: string; phone: string; terms: boolean;
  termsLanguage?: 'en' | 'fr'; termsEnglishRequested?: boolean;
  /** S-157: the ticked marketing checkboxes (unticked ones are not sent) */
  marketing?: MarketingConsent[];
}
export const CREATE_ACCOUNT_FIELDS = ['firstName', 'lastName', 'email', 'password', 'passwordConfirm', 'phone', 'terms'] as const;

export const createAccountSchema = (t: AuthKitT) => z.object({
  firstName: z.string().trim().min(1, t('firstNameRequired')),
  lastName: z.string().trim().min(1, t('lastNameRequired')),
  email: z.string().trim().min(1, t('emailRequired')).regex(EMAIL_PATTERN, t('emailFormat')),
  password: passwordSchema(t),
  passwordConfirm: z.string(),
  phone: z.string().trim().refine(p => p === '' || PHONE_PATTERN.test(p), t('phoneFormat')),
  terms: z.boolean().refine(v => v, t('termsRequired')),
}).superRefine((v, ctx) => {
  if (v.password && v.passwordConfirm !== v.password) ctx.addIssue({ code: 'custom', path: ['passwordConfirm'], message: t('passwordConfirm') });
  if (v.password && v.email && v.password.trim().toLowerCase() === v.email.trim().toLowerCase()) ctx.addIssue({ code: 'custom', path: ['password'], message: t('passwordIsEmail') });
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
/**
 * `continueTo` (S-29): a mobile app's authorization request that sent the browser here, to go back to.
 * `needsSecondFactor` (S-154): the account has no passkey and no authenticator yet — enrol one before the hand-off.
 */
export const AuthSession = z.object({ user: AuthUser, acr: z.string().nullish(), continueTo: z.string().nullish(), needsSecondFactor: z.boolean().optional() });
export type AuthSession = z.infer<typeof AuthSession>;
/** S-62: a sign-in code is on its way (the same answer whether or not an account matched). */
export const CodeSent = z.object({ resendAfterSeconds: z.number(), channel: z.enum(['sms', 'voice']) });
export type CodeSent = z.infer<typeof CodeSent>;
export const TotpSetup = z.object({ secret: z.string(), otpauthUri: z.string(), qrCode: z.string() });
export type TotpSetup = z.infer<typeof TotpSetup>;
/** S-154: an account email is on its way (the same answer whether or not the address has an account). */
export const EmailSent = z.object({ step: z.literal('verify-email'), email: z.string(), resendAfterSeconds: z.number() });
export type EmailSent = z.infer<typeof EmailSent>;
/** S-154: after email + password — the account's own factors, enrolment (signed in), or its email still to confirm. */
export const PasswordStep = z.object({
  step: z.enum(['factor', 'enroll', 'verify-email']), factors: z.array(z.string()).nullish(), session: AuthSession.nullish(),
  email: z.string().nullish(), resendAfterSeconds: z.number().nullish(),
});
export type PasswordStep = z.infer<typeof PasswordStep>;
export const EmailConfirmed = z.object({ email: z.string(), signedIn: z.boolean(), session: AuthSession.nullish() });
export type EmailConfirmed = z.infer<typeof EmailConfirmed>;
/** S-154: the second factor is set up (acr=mfa); the ten backup codes are shown once. */
export const Enrolled = z.object({ session: AuthSession, backupCodes: z.array(z.string()) });
export type Enrolled = z.infer<typeof Enrolled>;
export const PasswordReset = z.object({ email: z.string() });
/**
 * S-157: the sign-up form's marketing checkboxes (`GET /api/auth/accounts/consent-wordings?app=`): per category the
 * words to show and their version, and who asks (CASL: legal name, mailing address, contact). Consumer: email and texts;
 * Studio: email; console: none.
 */
export const ConsentWordings = z.object({
  requester: z.string(),
  items: z.array(z.object({ category: z.enum(['marketing_email', 'marketing_sms']), channel: z.string(), wordingVersion: z.string(), wording: z.string() })),
});
export type ConsentWordings = z.infer<typeof ConsentWordings>;

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
  // S-154: email + password, the email code / link, enrolment, forgot password.
  createAccount: (v: CreateAccountValues, app: AccountApp, locale = 'en') => post('/api/auth/accounts', EmailSent, {
    firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(), password: v.password, passwordConfirm: v.passwordConfirm,
    phone: v.phone.trim() || null, terms: v.terms, termsLanguage: v.termsLanguage, termsEnglishRequested: v.termsEnglishRequested, app,
    marketing: v.marketing ?? [],
  }, language(locale)),
  consentWordings: (app: AccountApp, locale = 'en') => http(authUrl(`/api/auth/accounts/consent-wordings?app=${app}`), { headers: language(locale) }, ConsentWordings),
  resendVerification: (locale = 'en') => post('/api/auth/accounts/resend', EmailSent, {}, language(locale)),
  verifyEmail: (code: string) => post('/api/auth/accounts/verify', AuthSession, { code: code.trim() }),
  verifyEmailLink: (token: string) => post('/api/auth/accounts/verify-link', EmailConfirmed, { token }),
  signInWithPassword: (email: string, password: string, locale = 'en') => post('/api/auth/sign-in/password', PasswordStep, { email: email.trim(), password }, language(locale)),
  enrolTotpSetup: () => post('/api/auth/enroll/totp', TotpSetup),
  enrolTotp: (code: string) => post('/api/auth/enroll/totp/verify', Enrolled, { code: code.trim() }),
  enrolPasskeyOptions: () => post('/api/auth/enroll/passkey/options', PasskeyOptions),
  enrolPasskey: (credential: unknown) => post('/api/auth/enroll/passkey', Enrolled, { credential, label: 'Passkey' }),
  forgotPassword: (email: string, app: AccountApp, locale = 'en') => post('/api/auth/password/forgot', EmailSent, { email: email.trim(), app }, language(locale)),
  resetPassword: (v: { code?: string; token?: string; password: string; passwordConfirm?: string }) => post('/api/auth/password/reset', PasswordReset, v),
};

export const useAuthMutation = <A, R>(fn: (a: A) => Promise<R>) => useMutation({ mutationFn: fn });

/**
 * Google / Apple via the auth server (OIDC federation); it returns to the app's /sign-in or /register — the consumer
 * site passes `app: 'consumer'` so it comes back there (S-62).
 */
export const federationUrl = (provider: 'google' | 'apple', app?: 'consumer') =>
  authUrl(`/oauth2/authorization/${provider}${app ? `?app=${app}` : ''}`);
