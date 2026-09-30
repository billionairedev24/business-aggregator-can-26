import { describe, expect, it } from 'vitest';
import { ApiError, ValidationError } from '@northline/client';
import { federationUrl } from './api';
import { configureAuthOrigin } from './config';
import { fieldErrors, flowError, rateLimitedFor } from './errors';
import { KIT_MESSAGES, type AuthKitKey } from './messages';
import { appAuthorizationUrl, bffLoginUrl, safeNext } from './next';

const t = (key: AuthKitKey, values?: Record<string, unknown>) => KIT_MESSAGES.en[key].replace('{time}', String(values?.time ?? ''));
const fr = (key: AuthKitKey) => KIT_MESSAGES.fr[key];

describe('auth-kit', () => {
  it('translates the auth server’s 422 rules, so French users see French', () => {
    const err = new ValidationError([{ field: 'phone', rule: 'unique', message: 'An account already uses this mobile number. Sign in instead.' }, { field: 'code', rule: 'mismatch', message: 'x' }]);
    expect(fieldErrors(err, fr, 'signInCodeWrong')).toEqual({ phone: 'Un compte utilise déjà ce numéro de mobile. Connectez-vous plutôt.', code: 'Ce code n’a pas fonctionné. Vérifiez-le et réessayez.' });
  });

  it('maps flow failures: throttled, restarted, provider down, network', () => {
    expect(flowError(new ApiError(429, 'x', { code: 'rate_limited', retryAfterSeconds: 65 }), t)).toBe('Too many attempts. Try again in 1:05.');
    expect(rateLimitedFor(new ApiError(429, 'x', { code: 'rate_limited' }))).toBe(60);
    expect(flowError(new ApiError(409, 'x'), t)).toBe('That took too long. Start again.');
    expect(flowError(new ApiError(503, 'x', { code: 'code_not_sent' }), t, 'voice')).toBe("We couldn't call this number. Try again in a moment, or resend the code by text.");
    expect(flowError(new TypeError('fetch failed'), t)).toBe("We couldn't reach Northline. Check your connection and try again.");
  });

  it('keeps next local (S-20) and hands off to the BFF', () => {
    expect(safeNext('/cart?x=1')).toBe('/cart?x=1');
    for (const bad of ['//evil.example', '/\\evil.example', 'https://evil.example', '/\t/evil.example', undefined]) expect(safeNext(bad)).toBeUndefined();
    expect(bffLoginUrl('/providers/prairie-wrench/book')).toBe('/bff/login?next=%2Fproviders%2Fprairie-wrench%2Fbook');
  });

  it('follows only an app’s authorization request on northline-auth', () => {
    configureAuthOrigin('https://auth.example.ca/');
    expect(appAuthorizationUrl('https://auth.example.ca/oauth2/authorize?client_id=mobile-consumer')).toBe('https://auth.example.ca/oauth2/authorize?client_id=mobile-consumer');
    expect(appAuthorizationUrl('https://evil.example/oauth2/authorize')).toBeNull();
    expect(appAuthorizationUrl('https://auth.example.ca/login')).toBeNull();
  });

  it('starts Google / Apple on the auth server, coming back to the consumer site when asked', () => {
    configureAuthOrigin('https://auth.example.ca');
    expect(federationUrl('google')).toBe('https://auth.example.ca/oauth2/authorization/google');
    expect(federationUrl('apple', 'consumer')).toBe('https://auth.example.ca/oauth2/authorization/apple?app=consumer');
  });

  it('has French for every message', () => {
    expect(Object.keys(KIT_MESSAGES.fr).sort()).toEqual(Object.keys(KIT_MESSAGES.en).sort());
  });
});
