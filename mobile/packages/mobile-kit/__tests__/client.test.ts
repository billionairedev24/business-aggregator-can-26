import { ApiClient } from '../src/api/client';
import { ApiError, NetworkError, SignedOutError } from '../src/api/errors';
import { DpopSession } from '../src/auth/session';
import { PushHooks, setPushRegistrar } from '../src/push/hooks';
import { memorySecureStorage } from '../src/storage/secure';
import { API, ISSUER, checkApiProof, fakeAuthServer, fakeFetch } from './support/fakeServers';

const CONFIG = { issuer: ISSUER, clientId: 'mobile-consumer', redirectUri: 'ca.northline.app:/oauth2redirect', scopes: ['openid', 'profile'] };

function setup(apiHandler: (req: { url: string; headers: Record<string, string>; signed: boolean }) => { status: number; body?: unknown }) {
  const auth = fakeAuthServer();
  const seen = new Set<string>();
  const net = fakeFetch((req) =>
    req.url.startsWith(ISSUER) ? auth.handler(req) : apiHandler({ url: req.url, headers: req.headers, signed: checkApiProof(req, seen) }),
  );
  const storage = memorySecureStorage();
  const session = new DpopSession(CONFIG, storage, net.impl);
  const client = new ApiClient(API, session, () => 'en', net.impl);
  const signIn = async () => {
    const p = session.beginSignIn();
    await session.completeSignIn(`${CONFIG.redirectUri}?code=the-code&state=${p.state}`, p);
  };
  return { net, session, client, signIn, storage };
}

describe('public reads (auth: optional / none)', () => {
  it('goes anonymous for a guest: no Authorization, no proof, and no token request', async () => {
    const { client, net } = setup(({ headers }) => ({ status: 200, body: { anonymous: !headers.authorization && !headers.dpop } }));
    expect(await client.get('/geo/markets', { auth: 'optional' })).toEqual({ anonymous: true });
    expect(net.requests.some((r) => r.url.startsWith(ISSUER))).toBe(false);
  });

  it('signs the read when someone is signed in', async () => {
    const { client, signIn } = setup(({ signed }) => ({ status: 200, body: { signed } }));
    await signIn();
    expect(await client.get('/public/home', { auth: 'optional' })).toEqual({ signed: true });
    expect(await client.get('/public/home', { auth: 'none' })).toEqual({ signed: false });
  });

  it('falls back to anonymous when the sign-in ended on the way (refresh refused)', async () => {
    const { client, signIn, storage } = setup(({ signed }) => (signed ? { status: 401 } : { status: 200, body: { signed } }));
    await signIn();
    // the api refuses the token (signed out elsewhere) and the refresh token is dead too
    await storage.set('nl.oauth.refresh.v1', 'refresh-revoked');
    expect(await client.get('/geo/markets', { auth: 'optional' })).toEqual({ signed: false });
  });

  it('still refuses a personal call without a sign-in', async () => {
    const { client } = setup(() => ({ status: 200, body: {} }));
    await expect(client.get('/me')).rejects.toBeInstanceOf(SignedOutError);
  });
});

describe('flaky networks', () => {
  it('gives up a request that gets no answer in time as a NetworkError', async () => {
    jest.useFakeTimers();
    try {
      const hanging = jest.fn((_: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted')))),
      );
      const session = new DpopSession(CONFIG, memorySecureStorage(), hanging as unknown as typeof fetch);
      const client = new ApiClient(API, session, () => 'en', hanging as unknown as typeof fetch, 5_000);
      const call = client.get('/geo/markets', { auth: 'none' }).catch((e: unknown) => e);
      await jest.advanceTimersByTimeAsync(5_001);
      expect(await call).toBeInstanceOf(NetworkError);
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps the field rule and the whole problem body of an error', async () => {
    const { client } = setup(() => ({
      status: 429,
      body: { code: 'otp_throttled', retryAfterSeconds: 31, errors: [{ field: 'code', rule: 'mismatch', message: 'No.' }] },
    }));
    const e = (await client.post('/x', { auth: 'none' }).catch((x: unknown) => x)) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.errors[0]?.rule).toBe('mismatch');
    expect(e.fieldMessage('code')).toBe('No.');
    expect(e.body.retryAfterSeconds).toBe(31);
  });

  it('sends extra headers but never lets them replace the signature', async () => {
    const { client, signIn, net } = setup(({ signed }) => ({ status: 200, body: { signed } }));
    await signIn();
    expect(await client.post('/me/checkouts', { headers: { 'X-Step-Up': 'yes', Authorization: 'Bearer stolen' }, json: {} })).toEqual({ signed: true });
    const call = net.requests.find((r) => r.url.endsWith('/me/checkouts'))!;
    expect(call.headers['x-step-up']).toBe('yes');
    expect(call.headers.authorization).toMatch(/^DPoP /);
  });
});

describe('default headers', () => {
  it('sends them on every call, anonymous or signed; a call may override them', async () => {
    const net = fakeFetch(() => ({ status: 200, body: {} }));
    const session = new DpopSession(CONFIG, memorySecureStorage(), net.impl);
    const client = new ApiClient(API, session, () => 'en', net.impl, 5_000, () => ({ 'X-Northline-Guest': 'g_abcdefghijklmnop' }));
    await client.get('/cart', { auth: 'optional' });
    await client.get('/cart', { auth: 'none', headers: { 'X-Northline-Guest': 'g_other_guest_id_00' } });
    expect(net.requests.map((r) => r.headers['x-northline-guest'])).toEqual(['g_abcdefghijklmnop', 'g_other_guest_id_00']);
  });
});

describe('the push hook point (S-102)', () => {
  afterEach(() => setPushRegistrar(null));

  it('does nothing until a registrar is installed, then calls it best effort', async () => {
    const { client } = setup(() => ({ status: 200 }));
    expect(PushHooks.installed).toBe(false);
    await expect(PushHooks.signedIn(client)).resolves.toBeUndefined();
    const signedIn = jest.fn(async () => {
      throw new Error('no APNs token yet');
    });
    const signingOut = jest.fn(async () => undefined);
    setPushRegistrar({ signedIn, signingOut });
    await expect(PushHooks.signedIn(client)).resolves.toBeUndefined();
    await PushHooks.signingOut(client);
    expect(signedIn).toHaveBeenCalledWith(client);
    expect(signingOut).toHaveBeenCalledWith(client);
  });
});
