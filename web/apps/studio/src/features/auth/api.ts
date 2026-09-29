import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import { authUrl } from '../../lib/auth-server';
import { SessionUser } from '../../lib/session';
import type { AuthT } from './messages';

// ── validation (docs/spec/validation-rules.md § Registration — same rules and messages as the auth server) ──────────

export const PHONE_PATTERN = /^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export interface RegisterValues { firstName: string; lastName: string; phone: string; email: string; terms: boolean }
export const REGISTER_FIELDS = ['firstName', 'lastName', 'phone', 'email', 'terms'] as const;

export const registerSchema = (t: AuthT) => z.object({
  firstName: z.string().trim().min(1, t('firstNameRequired')),
  lastName: z.string().trim().min(1, t('lastNameRequired')),
  phone: z.string().trim().min(1, t('phoneRequired')).regex(PHONE_PATTERN, t('phoneFormat')),
  email: z.string().trim().min(1, t('emailRequired')).regex(EMAIL_PATTERN, t('emailFormat')),
  terms: z.boolean().refine(v => v, t('termsRequired')),
});

/** A 6-digit code (phone verification, authenticator app). */
export const codeSchema = (t: AuthT) => z.string().trim().min(1, t('codeRequired')).regex(/^\d{6}$/, t('codeFormat'));
export const backupCodeSchema = (t: AuthT) => z.string().trim().min(1, t('backupRequired'));

/** First message of a zod result, or undefined. */
export function firstIssue(schema: z.ZodType, value: unknown): string | undefined {
  const r = schema.safeParse(value);
  return r.success ? undefined : r.error.issues[0]?.message;
}

// ── responses ─────────────────────────────────────────────────────────────────────────────────────────────────────

export const RegistrationStep = z.object({ step: z.enum(['otp', 'mfa']), phone: z.string(), resendAfterSeconds: z.number(), channel: z.enum(['sms', 'voice']) });
export type RegistrationStep = z.infer<typeof RegistrationStep>;
export const SignInStarted = z.object({ identifier: z.string(), factors: z.array(z.string()) });
export const AuthSession = z.object({ user: SessionUser, acr: z.string().nullish() });
export type AuthSession = z.infer<typeof AuthSession>;
export const TotpSetup = z.object({ secret: z.string(), otpauthUri: z.string(), qrCode: z.string() });
export type TotpSetup = z.infer<typeof TotpSetup>;
/** WebAuthn options exactly as Spring Security serialises them (base64url bytes). */
export const PasskeyOptions = z.record(z.string(), z.unknown());

const post = <T>(path: string, schema: z.ZodType<T>, body: unknown = {}) => http(authUrl(path), { method: 'POST', body }, schema);

// ── northline-auth JSON API ───────────────────────────────────────────────────────────────────────────────────────

export const authApi = {
  register: (v: RegisterValues) => post('/api/auth/register', RegistrationStep, { ...v, firstName: v.firstName.trim(), lastName: v.lastName.trim(), phone: v.phone.trim(), email: v.email.trim() }),
  resend: (channel: 'sms' | 'voice') => post('/api/auth/register/resend', RegistrationStep, { channel }),
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
};

export const useAuthMutation = <A, R>(fn: (a: A) => Promise<R>) => useMutation({ mutationFn: fn });

/** Google / Apple via the auth server (OIDC federation); it returns to /sign-in or /register. */
export const federationUrl = (provider: 'google' | 'apple') => authUrl(`/oauth2/authorization/${provider}`);
