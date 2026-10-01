import { p256 } from '@noble/curves/nist.js';

import { fromBase64url, fromUtf8, utf8 } from '../../src/bytes';
import { accessTokenHash, htu } from '../../src/dpop/proof';
import { thumbprintOf, type PublicJwk } from '../../src/dpop/key';

export const ISSUER = 'https://auth.test.example';
export const API = 'https://api.test.example/api/v1';

export interface Proof {
  header: { typ: string; alg: string; jwk: PublicJwk };
  claims: { jti: string; htm: string; htu: string; iat: number; nonce?: string; ath?: string };
}

/** Checks a proof the way northline-auth and the api do (signature, typ, alg, htm/htu, single use). */
export function verifyProof(jws: string, seen: Set<string>): Proof {
  const [h, c, s] = jws.split('.');
  const header = JSON.parse(fromUtf8(fromBase64url(h!))) as Proof['header'];
  const claims = JSON.parse(fromUtf8(fromBase64url(c!))) as Proof['claims'];
  const { x, y } = header.jwk;
  const pub = new Uint8Array([4, ...fromBase64url(x), ...fromBase64url(y)]);
  if (!p256.verify(fromBase64url(s!), utf8(`${h}.${c}`), pub, { prehash: true, lowS: false })) throw new Error('bad signature');
  if (header.typ !== 'dpop+jwt' || header.alg !== 'ES256') throw new Error('bad header');
  if (seen.has(claims.jti)) throw new Error('replayed proof');
  seen.add(claims.jti);
  return { header, claims };
}

type Handler = (req: { url: string; method: string; headers: Record<string, string>; body: string }) => {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
};

/** A fetch whose answers come from `handler`; every request is recorded. */
export function fakeFetch(handler: Handler) {
  const requests: Array<{ url: string; method: string; headers: Record<string, string>; body: string }> = [];
  const impl = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers: Record<string, string> = {};
    Object.entries((init?.headers as Record<string, string>) ?? {}).forEach(([k, v]) => (headers[k.toLowerCase()] = v));
    const req = { url: String(input), method: init?.method ?? 'GET', headers, body: typeof init?.body === 'string' ? init.body : '' };
    requests.push(req);
    const out = handler(req);
    return new Response(out.body === undefined ? null : JSON.stringify(out.body), {
      status: out.status,
      headers: { 'content-type': 'application/json', ...(out.headers ?? {}) },
    });
  });
  return { impl: impl as unknown as typeof fetch, requests };
}

/**
 * northline-auth's token endpoint as far as the app sees it: a nonce on the first request, DPoP-bound tokens, refresh
 * tokens that rotate and end the sign-in when an old one comes back.
 */
export function fakeAuthServer() {
  const seen = new Set<string>();
  let nonce = 'n1';
  let issued = 0;
  const live = new Set<string>();
  const state = { jkt: '', tokenRequests: 0, revoked: [] as string[] };
  const handler: Handler = (req) => {
    if (req.url === `${ISSUER}/oauth2/revoke`) {
      state.revoked.push(new URLSearchParams(req.body).get('token') ?? '');
      return { status: 200 };
    }
    if (req.url !== `${ISSUER}/oauth2/token`) return { status: 404 };
    state.tokenRequests++;
    const proof = verifyProof(req.headers.dpop!, seen);
    if (proof.claims.htm !== 'POST' || proof.claims.htu !== `${ISSUER}/oauth2/token`) return { status: 400, body: { error: 'invalid_dpop_proof' } };
    if (proof.claims.nonce !== nonce) return { status: 400, body: { error: 'use_dpop_nonce' }, headers: { 'DPoP-Nonce': nonce } };
    const form = new URLSearchParams(req.body);
    const jkt = thumbprintOf(proof.header.jwk);
    if (form.get('grant_type') === 'refresh_token') {
      const old = form.get('refresh_token')!;
      if (!live.has(old) || jkt !== state.jkt) {
        live.clear();
        return { status: 400, body: { error: 'invalid_grant' }, headers: { 'DPoP-Nonce': nonce } };
      }
      live.delete(old);
    } else if (form.get('code') !== 'the-code' || !form.get('code_verifier')) {
      return { status: 400, body: { error: 'invalid_grant' } };
    }
    state.jkt = jkt;
    issued++;
    const refresh = `refresh-${issued}`;
    live.add(refresh);
    nonce = `n${issued + 1}`;
    return {
      status: 200,
      body: { access_token: `access-${issued}`, token_type: 'DPoP', expires_in: 599, refresh_token: refresh, scope: 'openid courier deliveries' },
      headers: { 'DPoP-Nonce': nonce },
    };
  };
  return { handler, state, seen };
}

/** The api's DPoP check: the token, a proof for this request with `ath`. */
export function checkApiProof(req: { url: string; method: string; headers: Record<string, string> }, seen: Set<string>) {
  const auth = req.headers.authorization ?? '';
  if (!auth.startsWith('DPoP ')) return false;
  const proof = verifyProof(req.headers.dpop!, seen);
  return proof.claims.htm === req.method && proof.claims.htu === htu(req.url) && proof.claims.ath === accessTokenHash(auth.slice(5)) && !proof.claims.nonce;
}
