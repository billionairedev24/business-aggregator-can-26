import { NetworkError } from '../src/api/errors';
import { AppSignIn } from '../src/auth/handoff';
import { DpopSession, OAuthError } from '../src/auth/session';
import { memorySecureStorage } from '../src/storage/secure';
import { ISSUER, fakeAuthServer } from './support/fakeServers';

const SITE_REDIRECT = 'https://site.test.example/app/oauth2redirect';
const CONFIG = { issuer: ISSUER, clientId: 'mobile-consumer', redirectUri: 'ca.northline.app:/oauth2redirect', scopes: ['openid', 'profile'] };

/**
 * northline-auth's cookie session as the app's HTTP stack sees it: an unauthenticated authorization request is kept
 * and lands on the sign-in page; once signed in (the JSON API, simulated by `signIn()`), the kept request redirects to
 * the redirect URI with a code — fetch follows it, so `res.url` is where it landed.
 */
function authWithSession() {
  const tokens = fakeAuthServer();
  let saved: string | null = null;
  let signedIn = false;
  const gets: string[] = [];
  const landed = (url: string, status = 200) => {
    const res = new Response('<html></html>', { status, headers: { 'content-type': 'text/html' } });
    Object.defineProperty(res, 'url', { value: url });
    return res;
  };
  const impl = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith(`${ISSUER}/oauth2/authorize`)) {
      gets.push(url);
      expect(init?.credentials).toBe('include');
      const q = new URL(url).searchParams;
      if (!signedIn) {
        saved = url;
        return landed('https://site.test.example/sign-in');
      }
      return landed(`${q.get('redirect_uri')}?code=the-code&state=${q.get('state')}`);
    }
    const headers: Record<string, string> = {};
    Object.entries((init?.headers as Record<string, string>) ?? {}).forEach(([k, v]) => (headers[k.toLowerCase()] = v));
    const out = tokens.handler({ url, method: init?.method ?? 'GET', headers, body: typeof init?.body === 'string' ? init.body : '' });
    return new Response(out.body === undefined ? null : JSON.stringify(out.body), { status: out.status, headers: { 'content-type': 'application/json', ...(out.headers ?? {}) } });
  });
  return {
    fetch: impl as unknown as typeof fetch,
    gets,
    signIn: () => {
      signedIn = true;
      const c = saved;
      saved = null; // AppAuthorizationResume hands it out once
      return c;
    },
    tokens,
  };
}

describe('the in-app sign-in hand-off', () => {
  it('keeps the authorization request in the session, then follows continueTo to the https redirect and gets DPoP tokens', async () => {
    const auth = authWithSession();
    const storage = memorySecureStorage();
    const session = new DpopSession(CONFIG, storage, auth.fetch);
    const flow = new AppSignIn(session, SITE_REDIRECT, auth.fetch);
    const pending = await flow.begin();
    const q = new URL(auth.gets[0]!).searchParams;
    expect(q.get('redirect_uri')).toBe(SITE_REDIRECT);
    expect(q.get('code_challenge_method')).toBe('S256');
    const continueTo = auth.signIn();
    await flow.complete(continueTo, pending);
    expect(await session.accessToken()).toBe('access-1');
    // the code exchange repeated the https redirect the request named
    expect(await session.restore()).toBe(true);
  });

  it('asks again when the answer had no continueTo (the session is signed in, so a code comes at once)', async () => {
    const auth = authWithSession();
    const session = new DpopSession(CONFIG, memorySecureStorage(), auth.fetch);
    const flow = new AppSignIn(session, SITE_REDIRECT, auth.fetch);
    const pending = await flow.begin();
    auth.signIn();
    await flow.complete(null, pending);
    expect(auth.gets).toHaveLength(2);
    expect(await session.restore()).toBe(true);
  });

  it('follows only this client’s request with this state on the issuer, whatever host continueTo names', async () => {
    const auth = authWithSession();
    const session = new DpopSession(CONFIG, memorySecureStorage(), auth.fetch);
    const flow = new AppSignIn(session, SITE_REDIRECT, auth.fetch);
    const pending = await flow.begin();
    auth.signIn();
    const other = pending.url.replace(`state=${pending.state}`, 'state=someone-else');
    await expect(flow.complete(other, pending)).rejects.toMatchObject({ error: 'bad_continue' });
    await expect(flow.complete('https://evil.example/oauth2/authorize?client_id=x', pending)).rejects.toBeInstanceOf(OAuthError);
    // an absolute continueTo on another host (a proxy's view of the issuer) is re-based onto the issuer
    await flow.complete(pending.url.replace(ISSUER, 'http://auth-internal:9000'), pending);
    expect(auth.gets.at(-1)!.startsWith(`${ISSUER}/oauth2/authorize?`)).toBe(true);
  });

  it('says so when no code came back (the session was lost) or the network failed', async () => {
    const auth = authWithSession();
    const session = new DpopSession(CONFIG, memorySecureStorage(), auth.fetch);
    const flow = new AppSignIn(session, SITE_REDIRECT, auth.fetch);
    const pending = await flow.begin();
    await expect(flow.complete(pending.url, pending)).rejects.toMatchObject({ error: 'no_redirect' });
    const offline = new AppSignIn(session, SITE_REDIRECT, jest.fn(async () => {
      throw new TypeError('Network request failed');
    }) as unknown as typeof fetch);
    await expect(offline.begin()).rejects.toBeInstanceOf(NetworkError);
  });
});
