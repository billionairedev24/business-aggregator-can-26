import { ApiError, ValidationError } from '../../lib/http';
import type { AuthKey, AuthT } from './messages';
import { mmss } from './useCountdown';
import { PasskeyError } from './webauthn';

/** Server rule ids → the same messages the client shows (so French users see French for server-side 422s too). */
const RULES: Record<string, AuthKey> = {
  'firstName:required': 'firstNameRequired',
  'lastName:required': 'lastNameRequired',
  'phone:required': 'phoneRequired',
  'phone:format': 'phoneFormat',
  'phone:unique': 'phoneTaken',
  'email:required': 'emailRequired',
  'email:format': 'emailFormat',
  'email:unique': 'emailTaken',
  'terms:required': 'termsRequired',
  'code:required': 'codeRequired',
  'code:format': 'codeFormat',
  'code:expired': 'codeExpired',
  'code:locked': 'codeLocked',
  'credential:passkey': 'passkeyFailed',
  'identifier:required': 'identifierRequired',
};

/** 422 → { field: message }, translated where the rule is known. `mismatch` depends on the step (see callers). */
export function fieldErrors(err: unknown, t: AuthT, mismatch: AuthKey = 'codeWrong'): Record<string, string> {
  if (!(err instanceof ValidationError)) return {};
  const out: Record<string, string> = {};
  for (const e of err.errors) {
    const key = e.rule === 'mismatch' ? mismatch : RULES[`${e.field}:${e.rule}`];
    out[e.field] ??= key ? t(key) : e.message;
  }
  return out;
}

/** Anything that isn't a field error: flow restarted, throttled, passkey problems, network. */
export function flowError(err: unknown, t: AuthT): string | undefined {
  if (err instanceof ValidationError) return undefined;
  if (err instanceof PasskeyError) return t(err.reason === 'unsupported' ? 'passkeyUnsupported' : 'passkeyCancelled');
  if (err instanceof ApiError) {
    const code = err.body && typeof err.body === 'object' && 'code' in err.body ? String((err.body as { code: unknown }).code) : '';
    if (code === 'too_many_attempts') return t('tooMany');
    if (code === 'rate_limited') {
      const s = rateLimitedFor(err);
      return s ? t('rateLimited', { time: mmss(s) }) : t('rateLimitedLater');
    }
    if (err.status === 409) return t('restart');
    return err.message;
  }
  if (err instanceof TypeError) return t('network');
  return err instanceof Error ? err.message : String(err);
}

/** 429 `rate_limited` (S-9 limits per account / IP / session) → seconds to wait (Retry-After). */
export function rateLimitedFor(err: unknown): number | undefined {
  if (!(err instanceof ApiError) || err.status !== 429 || !err.body || typeof err.body !== 'object') return undefined;
  if ((err.body as { code?: unknown }).code !== 'rate_limited') return undefined;
  return retryAfter(err) ?? 60;
}

/** 429 otp_throttled → seconds to wait. */
export function retryAfter(err: unknown): number | undefined {
  if (!(err instanceof ApiError) || err.status !== 429 || !err.body || typeof err.body !== 'object') return undefined;
  const s = (err.body as { retryAfterSeconds?: unknown }).retryAfterSeconds;
  return typeof s === 'number' ? s : undefined;
}

/** Was the flow reset server-side (session expired / out of order)? */
export const isRestart = (err: unknown) => err instanceof ApiError && err.status === 409;
