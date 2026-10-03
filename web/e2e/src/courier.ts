import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign, type KeyObject } from 'node:crypto';
import { expect, type Page } from '@playwright/test';
import { env, type Persona } from './env';
import { studioSignIn } from './signIn';

const b64url = (data: Buffer | string) => Buffer.from(data).toString('base64url');

/** The courier app's key pair and its DPoP proofs (RFC 9449), ES256 — what the app keeps in the Keychain. */
class DpopKey {
  private readonly privateKey: KeyObject;
  readonly jwk: Record<string, string>;

  constructor() {
    const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    this.privateKey = privateKey;
    const { kty, crv, x, y } = publicKey.export({ format: 'jwk' }) as Record<string, string>;
    this.jwk = { kty: kty!, crv: crv!, x: x!, y: y! };
  }

  proof(htm: string, htu: string, extra: { nonce?: string; accessToken?: string } = {}): string {
    const header = b64url(JSON.stringify({ typ: 'dpop+jwt', alg: 'ES256', jwk: this.jwk }));
    const claims: Record<string, unknown> = { htm, htu, iat: Math.floor(Date.now() / 1000), jti: randomUUID() };
    if (extra.nonce) claims.nonce = extra.nonce;
    if (extra.accessToken) claims.ath = b64url(createHash('sha256').update(extra.accessToken).digest());
    const input = `${header}.${b64url(JSON.stringify(claims))}`;
    const signature = sign('sha256', Buffer.from(input), { key: this.privateKey, dsaEncoding: 'ieee-p1363' });
    return `${input}.${b64url(signature)}`;
  }
}

/**
 * The courier app (S-87) as the api sees it: northline-auth's `courier-app` client — authorization code + PKCE, then
 * DPoP-bound tokens — and the courier API (`/api/v1/courier/**`). The browser part (the sign-in, the authorization
 * redirect) runs in `page`; the app's own calls go from here, as the phone's would.
 */
export class CourierApp {
  private nonce: string | undefined;

  private constructor(private readonly key: DpopKey, private readonly accessToken: string) {}

  /** A courier persona with an authenticator app (target mode): signs in at northline-auth, then authorizes the app. */
  static async signIn(page: Page, persona: Persona): Promise<CourierApp> {
    await studioSignIn(page, persona); // northline-auth now has the courier's session (second factor)
    return CourierApp.authorize(page);
  }

  /** Authorization code + PKCE for `courier-app`, in a browser already signed in at northline-auth. */
  static async authorize(page: Page): Promise<CourierApp> {
    const verifier = b64url(randomBytes(32));
    const redirectUri = `${env.urls.consumer}/courier/oauth2redirect`;
    const query = new URLSearchParams({
      response_type: 'code', client_id: 'courier-app', redirect_uri: redirectUri, scope: 'openid courier deliveries',
      state: randomUUID(), code_challenge: b64url(createHash('sha256').update(verifier).digest()), code_challenge_method: 'S256',
    });
    // northline-auth redirects to the app's redirect URI (a page telling a browser to open the app): the code is in it
    await page.goto(`${env.urls.auth}/oauth2/authorize?${query}`);
    await expect(page).toHaveURL(url => url.href.startsWith(redirectUri) && url.searchParams.has('code'));
    const code = new URL(page.url()).searchParams.get('code');
    const key = new DpopKey();
    const tokens = await CourierApp.token(key, { grant_type: 'authorization_code', code: code!, redirect_uri: redirectUri, code_verifier: verifier });
    expect(tokens.token_type, JSON.stringify(tokens)).toBe('DPoP');
    return new CourierApp(key, tokens.access_token);
  }

  private static async token(key: DpopKey, params: Record<string, string>): Promise<{ access_token: string; token_type: string }> {
    const url = `${env.urls.auth}/oauth2/token`;
    let nonce: string | undefined;
    for (let attempt = 0; attempt < 2; attempt++) {
      const res = await fetch(url, {
        method: 'POST',
        headers: { DPoP: key.proof('POST', url, { nonce }), 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: 'courier-app', ...params }),
      });
      nonce = res.headers.get('dpop-nonce') ?? nonce;
      const body = (await res.json()) as { access_token: string; token_type: string; error?: string };
      if (body.error !== 'use_dpop_nonce') return body;
    }
    throw new Error('northline-auth kept asking for a DPoP nonce');
  }

  /** A courier API call with a fresh proof (and the api's nonce when it asks for one). */
  async call<T = unknown>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<{ status: number; body: T }> {
    const url = `${env.urls.courierApi}${path}`;
    for (let attempt = 0; attempt < 2; attempt++) {
      const res = await fetch(url, {
        method,
        headers: {
          Authorization: `DPoP ${this.accessToken}`,
          DPoP: this.key.proof(method, url, { nonce: this.nonce, accessToken: this.accessToken }),
          ...(body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const nonce = res.headers.get('dpop-nonce');
      if (res.status === 401 && nonce && nonce !== this.nonce) {
        this.nonce = nonce;
        continue;
      }
      const text = await res.text();
      expect(res.status, `${method} ${path}: ${text.slice(0, 300)}`).toBeLessThan(400);
      return { status: res.status, body: (text ? JSON.parse(text) : undefined) as T };
    }
    throw new Error(`${method} ${path}: the api kept asking for a DPoP nonce`);
  }
}
