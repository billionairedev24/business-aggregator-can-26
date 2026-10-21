import { ApiError, NetworkError, type FieldError } from '@northline/mobile-kit';

import { HandoffError } from './errors';

import type { MessageKey } from '../i18n';

/**
 * docs/spec/validation-rules.md § Registration on the phone — the same rules and messages as the auth server
 * (@northline/auth-kit's `createAccountSchema`, S-154: email + password, the mobile optional), and its 422 rules mapped
 * to the same messages so French people read French.
 */
export const PHONE_PATTERN = /^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export interface SignUpForm {
  fullName: string;
  email: string;
  password: string;
  passwordConfirm: string;
  /** Optional (S-154): stored unverified, for the courier and phone-code sign-in. */
  phone: string;
  terms: boolean;
}

/** NIST 800-63B as on the server: 12–128 characters as a person counts them (code points, after NFKC). */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 128;
export const passwordLength = (p: string) => [...p.normalize('NFKC')].length;

export function validatePassword(password: string, t: T, email = ''): string | undefined {
  if (!password) return t('authErr.passwordRequired');
  if (passwordLength(password) < PASSWORD_MIN) return t('authErr.passwordTooShort');
  if (passwordLength(password) > PASSWORD_MAX) return t('authErr.passwordTooLong');
  if (email && password.trim().toLowerCase() === email.trim().toLowerCase()) return t('authErr.passwordIsEmail');
  return undefined;
}
export type SignUpField = keyof SignUpForm;
type T = (key: MessageKey, params?: Record<string, string | number>) => string;

/** "Full name" is one field (design 01, as S-62): the last word is the last name, the rest the first name. */
export function splitName(fullName: string): { firstName: string; lastName: string } {
  const words = fullName.trim().split(/\s+/).filter(Boolean);
  if (words.length < 2) return { firstName: words[0] ?? '', lastName: '' };
  return { firstName: words.slice(0, -1).join(' '), lastName: words[words.length - 1]! };
}

export function validateSignUp(v: SignUpForm, t: T): Partial<Record<SignUpField, string>> {
  const out: Partial<Record<SignUpField, string>> = {};
  const { firstName, lastName } = splitName(v.fullName);
  if (!firstName) out.fullName = t('authErr.firstNameRequired');
  else if (!lastName) out.fullName = t('authErr.lastNameRequired');
  const email = v.email.trim();
  if (!email) out.email = t('authErr.emailRequired');
  else if (!EMAIL_PATTERN.test(email)) out.email = t('authErr.emailFormat');
  const password = validatePassword(v.password, t, email);
  if (password) out.password = password;
  else if (v.passwordConfirm !== v.password) out.passwordConfirm = t('authErr.passwordConfirm');
  const phone = v.phone.trim();
  if (phone && !PHONE_PATTERN.test(phone)) out.phone = t('authErr.phoneFormat');
  if (!v.terms) out.terms = t('authErr.termsRequired');
  return out;
}

export function validateEmail(email: string, t: T): string | undefined {
  const e = email.trim();
  if (!e) return t('authErr.emailRequired');
  if (!EMAIL_PATTERN.test(e)) return t('authErr.emailFormat');
  return undefined;
}

export function validateCode(code: string, t: T): string | undefined {
  const c = code.trim();
  if (!c) return t('authErr.codeRequired');
  if (!/^\d{6}$/.test(c)) return t('authErr.codeFormat');
  return undefined;
}

const RULES: Record<string, MessageKey> = {
  'firstName:required': 'authErr.firstNameRequired',
  'lastName:required': 'authErr.lastNameRequired',
  'phone:required': 'authErr.phoneRequired',
  'phone:format': 'authErr.phoneFormat',
  'phone:unique': 'authErr.phoneTaken',
  'email:required': 'authErr.emailRequired',
  'email:format': 'authErr.emailFormat',
  'email:unique': 'authErr.emailTaken',
  'terms:required': 'authErr.termsRequired',
  'code:required': 'authErr.codeRequired',
  'code:format': 'authErr.codeFormat',
  'code:expired': 'authErr.codeExpired',
  'code:locked': 'authErr.codeLocked',
  'identifier:required': 'authErr.identifierRequired',
  'password:required': 'authErr.passwordRequired',
  'password:email': 'authErr.passwordIsEmail',
  'password:common': 'authErr.passwordCommon',
  'passwordConfirm:mismatch': 'authErr.passwordConfirm',
};

/** A 422's field messages, translated where the rule is known (`mismatch` depends on the step). */
export function fieldErrors(err: unknown, t: T, mismatch: MessageKey = 'authErr.codeWrong'): Record<string, string> {
  if (!(err instanceof ApiError) || err.status !== 422) return {};
  const out: Record<string, string> = {};
  err.errors.forEach((e: FieldError) => {
    const field = e.field ?? 'form';
    // the code's "doesn't match" depends on the step; a password's is the sign-in's one message (S-154)
    const key = e.rule === 'mismatch' && field === 'code' ? mismatch : e.rule === 'mismatch' && field === 'password' ? 'authErr.credentials' : RULES[`${field}:${e.rule}`];
    out[field] ??= key ? t(key) : e.message;
  });
  return out;
}

const mmss = (s: number) => `${Math.floor(s / 60)}:${String(Math.max(0, s) % 60).padStart(2, '0')}`;

/** Anything that isn't a field error: restart, throttling, a code that couldn't be sent, the network. */
export function flowError(err: unknown, t: T, sent: 'form' | 'sms' | 'voice' = 'form'): string | undefined {
  if (err instanceof HandoffError) return err.reason instanceof NetworkError ? t('authErr.network') : t('done.failed');
  if (err instanceof NetworkError) return t('authErr.network');
  if (err instanceof ApiError) {
    if (err.status === 422) return undefined;
    if (err.code === 'too_many_attempts') return t('authErr.tooMany');
    if (err.code === 'sign_in_unavailable') return t('authErr.signInUnavailable');
    if (err.code === 'code_not_sent') return t(sent === 'voice' ? 'authErr.codeNotSentVoice' : sent === 'sms' ? 'authErr.codeNotSentSms' : 'authErr.codeNotSentForm');
    if (err.code === 'rate_limited') {
      const s = err.retryAfter;
      return s ? t('authErr.rateLimited', { time: mmss(s) }) : t('authErr.rateLimitedLater');
    }
    if (err.code === 'otp_throttled') {
      const s = typeof err.body.retryAfterSeconds === 'number' ? err.body.retryAfterSeconds : err.retryAfter;
      return s ? t('authErr.rateLimited', { time: mmss(s) }) : t('authErr.rateLimitedLater');
    }
    if (err.status === 409) return t('authErr.restart');
    return err.message;
  }
  return t('done.failed');
}

/** The flow was reset server-side (the auth session expired or the steps came out of order): start again. */
export const isRestart = (err: unknown) => err instanceof ApiError && err.status === 409;

export { mmss };
