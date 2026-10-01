import { ApiClient } from '../src/api/client';
import { ApiError, NetworkError, SignedOutError } from '../src/api/errors';
import { DpopSession, OAuthError } from '../src/auth/session';
import { codeChallenge } from '../src/auth/pkce';
import { memorySecureStorage } from '../src/storage/secure';
import { API, ISSUER, checkApiProof, fakeAuthServer, fakeFetch } from './support/fakeServers';

const CONFIG = { issuer: ISSUER, clientId: 'courier-app', redirectUri: 'ca.northline.courier:/oauth2redirect', scopes: ['openid', 'courier', 'deliveries'] };

async function signedIn(now = { t: Date.parse('2026-10-01T12:00:00Z') }) {
  const auth = fakeAuthServer();
  const net = fakeFetch(auth.handler);
  const storage = memorySecureStorage();
  const session = new DpopSession(CONFIG, storage, net.impl, () => now.t);
  const pending = session.beginSignIn();
  await session.completeSignIn(`${CONFIG.redirectUri}?code=the-code&state=${pending.state}`, pending);
  return { auth, net, storage, session, now };
}

describe('signing in', () => {
  it('asks for a code with PKCE S256, the courier scopes and a state', () => {
    const session = new DpopSession(CONFIG, memorySecureStorage());
    const pending = session.beginSignIn();
    const url = new URL(pending.url);
    expect(`${url.origin}${url.pathname}`).toBe(`${ISSUER}/oauth2/authorize`);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      response_type: 'code',
      client_id: 'courier-app',
      redirect_uri: 'ca.northline.courier:/oauth2redirect',
      scope: 'openid courier deliveries',
      state: pending.state,
      code_challenge: codeChallenge(pending.verifier),
      code_challenge_method: 'S256',
    });
  });

  it('exchanges the code with a proof, learning the nonce from the first answer, and keeps the refresh token', async () => {
    const { auth, net, storage, session } = await signedIn();
    expect(auth.state.tokenRequests).toBe(2); // use_dpop_nonce, then the same request with the nonce
    const exchange = new URLSearchParams(net.requests[1]!.body);
    expect(exchange.get('grant_type')).toBe('authorization_code');
    expect(exchange.get('client_id')).toBe('courier-app');
    expect(exchange.get('code_verifier')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(auth.state.jkt).toBe(session.deviceKey().thumbprint);
    expect(storage.values.get('nl.oauth.refresh.v1')).toBe('refresh-1');
    expect(await session.accessToken()).toBe('access-1');
    expect(await session.restore()).toBe(true);
  });

  it('refuses a redirect with another state or an error', async () => {
    const session = new DpopSession(CONFIG, memorySecureStorage(), fakeFetch(() => ({ status: 500 })).impl);
    const pending = session.beginSignIn();
    await expect(session.completeSignIn(`x:/r?code=c&state=other`, pending)).rejects.toMatchObject({ error: 'state_mismatch' });
    await expect(session.completeSignIn(`x:/r?error=access_denied&state=${pending.state}`, pending)).rejects.toBeInstanceOf(OAuthError);
  });
});

describe('refreshing', () => {
  it('refreshes once for concurrent callers, stores the rotated token, and reuses the nonce', async () => {
    const { auth, storage, session, now } = await signedIn();
    now.t += 10 * 60_000; // the access token expired
    const [a, b, c] = await Promise.all([session.accessToken(), session.accessToken(), session.accessToken()]);
    expect([a, b, c]).toEqual(['access-2', 'access-2', 'access-2']);
    expect(auth.state.tokenRequests).toBe(3); // one refresh, no nonce round-trip
    expect(storage.values.get('nl.oauth.refresh.v1')).toBe('refresh-2');
  });

  it('ends the sign-in on invalid_grant: tokens and key forgotten, listeners told', async () => {
    const { storage, session, now } = await signedIn();
    const changes: boolean[] = [];
    session.onChange((s) => changes.push(s));
    await storage.set('nl.oauth.refresh.v1', 'refresh-0'); // an already rotated token
    now.t += 10 * 60_000;
    await expect(session.accessToken()).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.values.size).toBe(0);
    expect(changes).toEqual([false]);
    expect(await session.restore()).toBe(false);
  });

  it('keeps the refresh token when the request never got an answer', async () => {
    const { storage, now, auth } = await signedIn();
    let offline = true;
    const net = fakeFetch((req) => {
      if (offline) throw new TypeError('Network request failed');
      return auth.handler(req);
    });
    const session = new DpopSession(CONFIG, storage, net.impl, () => now.t);
    await expect(session.accessToken()).rejects.toBeInstanceOf(NetworkError);
    expect(storage.values.get('nl.oauth.refresh.v1')).toBe('refresh-1');
    offline = false;
    expect(await session.accessToken()).toBe('access-2');
  });

  it('signs out by revoking the refresh token and deleting the key', async () => {
    const { auth, storage, session } = await signedIn();
    await session.signOut();
    expect(auth.state.revoked).toEqual(['refresh-1']);
    expect(storage.values.size).toBe(0);
    expect(() => session.deviceKey()).toThrow(SignedOutError);
  });
});

type ApiRequest = { url: string; method: string; headers: Record<string, string>; ok: boolean };

describe('calling the api', () => {
  async function client(apiHandler: (req: ApiRequest) => ReturnType<Parameters<typeof fakeFetch>[0]>) {
    const ctx = await signedIn();
    const seen = new Set<string>();
    const api = fakeFetch((req) => (req.url.startsWith(ISSUER) ? ctx.auth.handler(req) : apiHandler({ ...req, ok: checkApiProof(req, seen) })));
    const session = new DpopSession(CONFIG, ctx.storage, api.impl, () => ctx.now.t);
    await session.restore();
    return { ...ctx, api, client: new ApiClient(API, session, () => 'fr-CA', api.impl) };
  }

  it('sends the DPoP token with a proof for this request, the language and the idempotency key', async () => {
    const { client: c, api } = await client((req) => (req.ok ? { status: 200, body: { id: 'run-1' } } : { status: 401 }));
    expect(await c.post('/courier/stops/s1/arrive', { idempotencyKey: 'k-1' })).toEqual({ id: 'run-1' });
    const call = api.requests.find((r) => r.url.startsWith(API))!;
    expect(call.headers.authorization).toBe('DPoP access-2');
    expect(call.headers['accept-language']).toBe('fr-CA');
    expect(call.headers['idempotency-key']).toBe('k-1');
  });

  it('refreshes once on a 401 and retries with a new proof', async () => {
    let calls = 0;
    const { client: c, api } = await client(() => (++calls === 1 ? { status: 401 } : { status: 200, body: { ok: true } }));
    expect(await c.get('/courier/me')).toEqual({ ok: true });
    const apiCalls = api.requests.filter((r) => r.url.startsWith(API));
    expect(apiCalls).toHaveLength(2);
    expect(apiCalls[0]!.headers.dpop).not.toBe(apiCalls[1]!.headers.dpop);
    expect(apiCalls[1]!.headers.authorization).toBe('DPoP access-3');
  });

  it('answers 204 as null and errors as ApiError with the code, message and Retry-After', async () => {
    const { client: c } = await client((req) => {
      if (req.url.endsWith('/run')) return { status: 204 };
      if (req.url.endsWith('/location')) return { status: 429, body: { code: 'too_many_pings' }, headers: { 'Retry-After': '2' } };
      return { status: 422, body: { errors: [{ field: 'pin', message: "Enter the customer's 4-digit PIN." }] } };
    });
    expect(await c.get('/courier/run')).toBeNull();
    await expect(c.post('/courier/location', { json: {} })).rejects.toMatchObject({ status: 429, code: 'too_many_pings', retryAfter: 2, transient: true });
    const e = await c.post('/courier/stops/s/dropoff', { json: { proof: 'pin' } }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).message).toBe("Enter the customer's 4-digit PIN.");
    expect((e as ApiError).transient).toBe(false);
  });
});
