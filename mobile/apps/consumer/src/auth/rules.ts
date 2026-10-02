import { ApiError, NetworkError, type FieldError } from '@northline/mobile-kit';

import { HandoffError } from './errors';

import type { MessageKey } from '../i18n';

/**
 * docs/spec/validation-rules.md § Registration on the phone — the same rules and messages as the auth server
 * (@northline/auth-kit's `registerSchema`), and its 422 rules mapped to the same messages so French people read French.
 */
export const PHONE_PATTERN = /^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export interface SignUpForm {
  phone: string;
  fullName: string;
  email: string;
  terms: boolean;
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
  const phone = v.phone.trim();
  if (!phone) out.phone = t('authErr.phoneRequired');
  else if (!PHONE_PATTERN.test(phone)) out.phone = t('authErr.phoneFormat');
  const { firstName, lastName } = splitName(v.fullName);
  if (!firstName) out.fullName = t('authErr.firstNameRequired');
  else if (!lastName) out.fullName = t('authErr.lastNameRequired');
  const email = v.email.trim();
  if (!email) out.email = t('authErr.emailRequired');
  else if (!EMAIL_PATTERN.test(email)) out.email = t('authErr.emailFormat');
  if (!v.terms) out.terms = t('authErr.termsRequired');
  return out;
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
};

/** A 422's field messages, translated where the rule is known (`mismatch` depends on the step). */
export function fieldErrors(err: unknown, t: T, mismatch: MessageKey = 'authErr.codeWrong'): Record<string, string> {
  if (!(err instanceof ApiError) || err.status !== 422) return {};
  const out: Record<string, string> = {};
  err.errors.forEach((e: FieldError) => {
    const field = e.field ?? 'form';
    const key = e.rule === 'mismatch' ? mismatch : RULES[`${field}:${e.rule}`];
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
