import { fromBase64url, fromUtf8 } from '@northline/mobile-kit';

import type { FixtureArea, FixtureContext } from './context';

/**
 * northline-auth as far as the app sees it: DPoP token answers with a nonce (`use_dpop_nonce` first, as the real one
 * does on a fresh install) and rotating refresh tokens, revocation at sign-out.
 */
export interface AuthFixtureState {
  issued: number;
  revoked: string[];
  /** Refresh tokens still good (rotation: each is good once). */
  live: Set<string>;
  nonce: string | null;
}

export function authFixtures(ctx: FixtureContext, state: AuthFixtureState): FixtureArea {
  return (req) => {
    if (req.path === '/oauth2/revoke') {
      state.revoked.push(String(req.body.token ?? ''));
      state.live.delete(String(req.body.token ?? ''));
      return ctx.answer(200, {});
    }
    if (req.path !== '/oauth2/token') return undefined;
    if (!req.headers.dpop) return ctx.answer(400, { error: 'invalid_dpop_proof' });
    const proofNonce = JSON.parse(fromUtf8(fromBase64url(req.headers.dpop.split('.')[1] ?? ''))).nonce as string | undefined;
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
  };
}

