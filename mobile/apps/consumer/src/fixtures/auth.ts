import { fromBase64url, fromUtf8 } from '@northline/mobile-kit';

import type { FixtureArea, FixtureContext, FixtureRequest } from './context';

/**
 * northline-auth as far as the app sees it (S-97, S-98):
 * - the token endpoint: DPoP answers with a nonce (`use_dpop_nonce` first, as on a fresh install), rotating refresh
 *   tokens, revocation at sign-out (which ends the cookie session too — single sign-out);
 * - `/oauth2/authorize` in the app's cookie session: kept while signed out (the answer "lands" on the consumer site's
 *   sign-in page), a code on the https redirect once signed in — `Response.url` as fetch reports a followed redirect;
 * - the JSON sign-in API of S-62: register → verify → totp | complete, sign-in → code → verify, with `continueTo`.
 * Codes: SMS / voice {@link FIXTURE_CODE}, authenticator {@link FIXTURE_TOTP}. An account exists for
 * {@link FIXTURE_ACCOUNT} (made-up person and number).
 */
export const FIXTURE_CODE = '246810';
export const FIXTURE_TOTP = '135790';
export const FIXTURE_ACCOUNT = { phone: '+1 555 555 0100', email: 'ada@example.com', firstName: 'Ada', lastName: 'Example' };
const SIGN_IN_PAGE = 'https://site.fixtures.invalid/sign-in';
const RESEND_AFTER = 45;

interface Account {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
}

export interface AuthFixtureState {
  issued: number;
  revoked: string[];
  /** Refresh tokens still good (rotation: each is good once). */
  live: Set<string>;
  nonce: string | null;
  /** The cookie session of the app's HTTP stack. */
  session: {
    saved: string | null;
    signedIn: Account | null;
    registration: { account: Account; verified: boolean; sentAt: number; tries: number } | null;
    signIn: { account: Account | null; sentAt: number } | null;
  };
  accounts: Account[];
  /** Every JSON API call: path and body (tests read what the screens sent). */
  api: Array<{ path: string; body: Record<string, unknown>; language?: string }>;
}

export function newAuthState(): AuthFixtureState {
  return {
    issued: 0,
    revoked: [],
    live: new Set(),
    nonce: null,
    session: { saved: null, signedIn: null, registration: null, signIn: null },
    accounts: [{ id: 'acct-ada', ...FIXTURE_ACCOUNT }],
    api: [],
  };
}

const digits = (phone: string) => phone.replace(/\D/g, '').replace(/^1(?=\d{10}$)/, '');
const display = (phone: string) => {
  const d = digits(phone);
  return d.length === 10 ? `+1 (${d.slice(0, 3)}) ${d.slice(3, 6)}-${d.slice(6)}` : phone;
};

function landed(url: string): Response {
  const res = new Response('<!doctype html><title>Northline</title>', { status: 200, headers: { 'content-type': 'text/html' } });
  Object.defineProperty(res, 'url', { value: url });
  return res;
}

export function authFixtures(ctx: FixtureContext, state: AuthFixtureState): FixtureArea {
  const s = state.session;
  const user = (a: Account) => ({ id: a.id, firstName: a.firstName, lastName: a.lastName, email: a.email, phone: a.phone, initials: `${a.firstName[0]}${a.lastName[0]}` });
  let lastSignedIn: string | null = null;
  const signedIn = (a: Account, acr: string | null, status = 200) => {
    s.signedIn = a;
    lastSignedIn = a.id;
    s.registration = null;
    s.signIn = null;
    const continueTo = s.saved;
    s.saved = null; // AppAuthorizationResume hands it out once
    return ctx.answer(status, { user: user(a), acr, continueTo });
  };
  const invalid = (field: string, rule: string, message: string) => ctx.answer(422, { errors: [{ field, rule, message }] });
  const notStarted = () => ctx.answer(409, { code: 'flow_not_started', detail: 'Start again.' });
  const throttled = (sentAt: number) => {
    const wait = RESEND_AFTER - Math.floor((ctx.now() - sentAt) / 1000);
    return wait > 0 ? ctx.answer(429, { code: 'otp_throttled', detail: 'Wait before asking for a new code.', retryAfterSeconds: wait }, { 'Retry-After': String(wait) }) : null;
  };
  const code = (req: FixtureRequest) => String(req.body.code ?? '').trim();

  return (req) => {
    const path = req.path;
    if (path === '/oauth2/revoke') {
      state.revoked.push(String(req.body.token ?? ''));
      state.live.delete(String(req.body.token ?? ''));
      s.signedIn = null;
      return ctx.answer(200, {});
    }
    if (path === '/oauth2/authorize') {
      const q = req.url.searchParams;
      if (!s.signedIn) {
        s.saved = req.url.toString();
        return landed(SIGN_IN_PAGE);
      }
      return landed(`${q.get('redirect_uri')}?code=fixture-code&state=${q.get('state')}`);
    }
    if (path === '/oauth2/token') return token(req);
    if (path === '/me') {
      // the api's GET /api/v1/me for the token's person (the fixture trusts any DPoP token: the last sign-in's, else the existing account — the browser sign-in)
      const a = state.accounts.find((x) => x.id === lastSignedIn) ?? state.accounts[0];
      if (!(req.headers.authorization ?? '').startsWith('DPoP ') || !a) return ctx.answer(401, { error: 'invalid_token' });
      return ctx.answer(200, { ...user(a), locale: 'en-CA', memberSince: '2026-10-02', mfaPrimary: null, mfa: false });
    }
    if (!path.startsWith('/api/auth/')) return undefined;
    state.api.push({ path, body: req.body, language: req.headers['accept-language'] });

    switch (path) {
      case '/api/auth/register': {
        const b = req.body as Record<string, string | boolean>;
        if (state.accounts.some((a) => digits(a.phone) === digits(String(b.phone)))) return invalid('phone', 'unique', 'An account already uses this mobile number. Sign in instead.');
        if (state.accounts.some((a) => a.email === String(b.email).toLowerCase())) return invalid('email', 'unique', 'An account already uses this email. Sign in instead.');
        const account: Account = { id: `acct-${state.accounts.length + 1}`, firstName: String(b.firstName), lastName: String(b.lastName), email: String(b.email).toLowerCase(), phone: String(b.phone) };
        s.registration = { account, verified: false, sentAt: ctx.now(), tries: 0 };
        return ctx.answer(200, { step: 'otp', phone: display(account.phone), resendAfterSeconds: RESEND_AFTER, channel: 'sms' });
      }
      case '/api/auth/register/resend': {
        if (!s.registration) return notStarted();
        const wait = throttled(s.registration.sentAt);
        if (wait) return wait;
        s.registration.sentAt = ctx.now();
        return ctx.answer(200, { step: 'otp', phone: display(s.registration.account.phone), resendAfterSeconds: RESEND_AFTER, channel: req.body.channel === 'voice' ? 'voice' : 'sms' });
      }
      case '/api/auth/register/verify': {
        if (!s.registration) return notStarted();
        if (code(req) !== FIXTURE_CODE) {
          s.registration.tries++;
          return s.registration.tries >= 5 ? invalid('code', 'locked', 'Too many tries. Send a new code.') : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.");
        }
        s.registration.verified = true;
        return ctx.answer(200, { step: 'mfa', phone: display(s.registration.account.phone), resendAfterSeconds: 0, channel: 'sms' });
      }
      case '/api/auth/register/totp':
        if (!s.registration?.verified) return notStarted();
        return ctx.answer(200, {
          secret: 'JBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/Northline:ada?secret=JBSWY3DPEHPK3PXP&issuer=Northline',
          qrCode: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
        });
      case '/api/auth/register/totp/verify': {
        if (!s.registration?.verified) return notStarted();
        if (code(req) !== FIXTURE_TOTP) return invalid('code', 'mismatch', "That code doesn't match. Check it and try again.");
        const a = s.registration.account;
        state.accounts.push(a);
        return signedIn(a, 'mfa', 201);
      }
      case '/api/auth/register/complete': {
        if (!s.registration?.verified) return notStarted();
        const a = s.registration.account;
        state.accounts.push(a);
        return signedIn(a, null, 201);
      }
      case '/api/auth/sign-in': {
        const id = String(req.body.identifier ?? '').trim();
        if (!id) return invalid('identifier', 'required', 'Enter your email or mobile.');
        const account = state.accounts.find((a) => a.email === id.toLowerCase() || (digits(id).length === 10 && digits(a.phone) === digits(id))) ?? null;
        s.signIn = { account, sentAt: 0 };
        return ctx.answer(200, { identifier: id, factors: ['passkey', 'totp', 'backup_code'] });
      }
      case '/api/auth/sign-in/code': {
        if (!s.signIn) return notStarted();
        const wait = s.signIn.sentAt ? throttled(s.signIn.sentAt) : null;
        if (wait) return wait;
        s.signIn.sentAt = ctx.now();
        // the same answer whether or not an account matched (S-62)
        return ctx.answer(200, { resendAfterSeconds: RESEND_AFTER, channel: req.body.channel === 'voice' ? 'voice' : 'sms' });
      }
      case '/api/auth/sign-in/code/verify': {
        if (!s.signIn) return notStarted();
        if (!s.signIn.account || code(req) !== FIXTURE_CODE) return invalid('code', 'mismatch', "That code didn't work. Check it and try again.");
        return signedIn(s.signIn.account, null);
      }
    }
    return ctx.answer(404, { code: 'not_found' });
  };

  function token(req: FixtureRequest): Response {
    if (!req.headers.dpop) return ctx.answer(400, { error: 'invalid_dpop_proof' });
    const proofNonce = (JSON.parse(fromUtf8(fromBase64url(req.headers.dpop.split('.')[1] ?? ''))) as { nonce?: string }).nonce;
    if (!state.nonce || proofNonce !== state.nonce) {
      state.nonce = `n${state.issued + 1}`;
      return ctx.answer(400, { error: 'use_dpop_nonce' }, { 'DPoP-Nonce': state.nonce });
    }
    if (req.body.grant_type === 'refresh_token') {
      const old = String(req.body.refresh_token ?? '');
      if (!state.live.has(old)) return ctx.answer(400, { error: 'invalid_grant' }, { 'DPoP-Nonce': state.nonce });
      state.live.delete(old);
    }
    state.issued++;
    const refresh = `fixture-refresh-${state.issued}`;
    state.live.add(refresh);
    state.nonce = `n${state.issued + 1}`;
    return ctx.answer(
      200,
      { access_token: `fixture-access-${state.issued}`, token_type: 'DPoP', expires_in: 599, refresh_token: refresh, scope: 'openid profile orders bookings offline_access' },
      { 'DPoP-Nonce': state.nonce },
    );
  }
}
