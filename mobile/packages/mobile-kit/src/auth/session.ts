import { NetworkError, SignedOutError } from '../api/errors';
import { createDeviceKey, deleteDeviceKey, loadDeviceKey, type DeviceKey } from '../dpop/key';
import { createProof, ServerClock } from '../dpop/proof';
import { randomToken } from '../dpop/random';
import type { SecureStorage } from '../storage/secure';
import { codeChallenge, codeVerifier } from './pkce';

/** A public, DPoP-bound OAuth client of northline-auth (S-29; docs/runbooks/mobile-auth.md). */
export interface OAuthConfig {
  /** `https://auth.<zone>` (no trailing slash). */
  issuer: string;
  clientId: string;
  /** Registered exactly (`ca.northline.courier:/oauth2redirect`). */
  redirectUri: string;
  scopes: readonly string[];
}

export class OAuthError extends Error {
  constructor(
    readonly status: number,
    readonly error: string,
    readonly description?: string,
  ) {
    super(description ?? error);
    this.name = 'OAuthError';
  }
}

/** What the app keeps between the authorization request and the redirect. */
export interface PendingSignIn {
  url: string;
  state: string;
  verifier: string;
}

type Fetch = typeof fetch;

const REFRESH_ITEM = 'nl.oauth.refresh.v1';
/** Refresh this long before the access token expires (10 min tokens). */
const EARLY_REFRESH_MS = 60_000;

/**
 * The person's sign-in on this phone: PKCE in the system browser, DPoP-bound tokens, one refresh at a time.
 *
 * - The refresh token lives in secure storage and is replaced **before** anything from a refresh answer is used
 *   (a rotated token is dead; presenting it again ends the sign-in).
 * - Refreshes are single-flight: concurrent callers wait for the one in progress.
 * - `invalid_grant` ends the session locally; listeners hear `signedOut`.
 * - A refresh that got no answer at all (offline) keeps the token and fails with {@link NetworkError}; the next try
 *   presents it again. If the server had rotated it, that ends the sign-in (reuse detection) and the courier signs in
 *   again — the trade-off the runbook allows (§ 4), chosen because couriers lose signal often.
 */
export class DpopSession {
  readonly clock: ServerClock;
  private key: DeviceKey | null = null;
  private access: { token: string; expiresAt: number } | null = null;
  private authNonce: string | undefined;
  private refreshing: Promise<string> | null = null;
  private listeners = new Set<(signedIn: boolean) => void>();
  private restored = false;

  constructor(
    readonly config: OAuthConfig,
    private readonly storage: SecureStorage,
    private readonly fetchImpl: Fetch = (...a) => fetch(...a),
    private readonly now: () => number = Date.now,
  ) {
    this.clock = new ServerClock(now);
  }

  get tokenEndpoint(): string {
    return `${this.config.issuer}/oauth2/token`;
  }

  /** Reads the key and refresh token a previous run left; true when there is a sign-in to continue. */
  async restore(): Promise<boolean> {
    if (!this.restored) {
      this.key = await loadDeviceKey(this.storage);
      this.restored = true;
    }
    return this.key !== null && (await this.storage.get(REFRESH_ITEM)) !== null;
  }

  onChange(listener: (signedIn: boolean) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private emit(signedIn: boolean) {
    this.listeners.forEach((l) => l(signedIn));
  }

  /** The authorization request to open in the system browser (ASWebAuthenticationSession / Custom Tabs). */
  beginSignIn(extra: Record<string, string> = {}): PendingSignIn {
    const verifier = codeVerifier();
    const state = randomToken(16);
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: this.config.clientId,
      redirect_uri: this.config.redirectUri,
      scope: this.config.scopes.join(' '),
      state,
      code_challenge: codeChallenge(verifier),
      code_challenge_method: 'S256',
      ...extra,
    });
    return { url: `${this.config.issuer}/oauth2/authorize?${params.toString()}`, state, verifier };
  }

  /** The redirect came back: check it, create this sign-in's key and exchange the code. */
  async completeSignIn(redirectUrl: string, pending: PendingSignIn): Promise<void> {
    const query = new URLSearchParams(redirectUrl.includes('?') ? redirectUrl.slice(redirectUrl.indexOf('?') + 1) : '');
    if (query.get('state') !== pending.state) throw new OAuthError(400, 'state_mismatch');
    const error = query.get('error');
    if (error) throw new OAuthError(400, error, query.get('error_description') ?? undefined);
    const code = query.get('code');
    if (!code) throw new OAuthError(400, 'no_code');
    this.key = await createDeviceKey(this.storage);
    this.restored = true;
    const tokens = await this.tokenRequest({
      grant_type: 'authorization_code',
      code,
      redirect_uri: this.config.redirectUri,
      code_verifier: pending.verifier,
    });
    await this.accept(tokens);
    this.emit(true);
  }

  /** A valid access token, refreshed first when it is about to expire. */
  async accessToken(): Promise<string> {
    if (this.access && this.access.expiresAt - EARLY_REFRESH_MS > this.now()) return this.access.token;
    return this.refresh();
  }

  /** One refresh at a time; concurrent callers share it. */
  refresh(): Promise<string> {
    this.refreshing ??= this.doRefresh().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  /** The key this sign-in's tokens are bound to. */
  deviceKey(): DeviceKey {
    if (!this.key) throw new SignedOutError('no_key');
    return this.key;
  }

  private async doRefresh(): Promise<string> {
    await this.restore();
    const refreshToken = await this.storage.get(REFRESH_ITEM);
    if (!this.key || !refreshToken) throw new SignedOutError();
    try {
      const tokens = await this.tokenRequest({ grant_type: 'refresh_token', refresh_token: refreshToken });
      return await this.accept(tokens);
    } catch (e) {
      if (e instanceof OAuthError && (e.error === 'invalid_grant' || e.error === 'invalid_client')) {
        await this.clear();
        throw new SignedOutError(e.error);
      }
      throw e;
    }
  }

  private async accept(tokens: Record<string, unknown>): Promise<string> {
    const access = tokens.access_token;
    if (typeof access !== 'string' || tokens.token_type !== 'DPoP') {
      throw new OAuthError(500, 'not_dpop', 'The token answer is not a DPoP-bound token');
    }
    // Store the new refresh token before anything else: the old one is dead from now on.
    if (typeof tokens.refresh_token === 'string') await this.storage.set(REFRESH_ITEM, tokens.refresh_token);
    const expiresIn = typeof tokens.expires_in === 'number' ? tokens.expires_in : 600;
    this.access = { token: access, expiresAt: this.now() + expiresIn * 1000 };
    return access;
  }

  /** POST /oauth2/token with a proof; a `use_dpop_nonce` answer is retried once with the nonce it carries. */
  private async tokenRequest(params: Record<string, string>): Promise<Record<string, unknown>> {
    const key = this.deviceKey();
    for (let attempt = 0; ; attempt++) {
      const proof = await createProof(key, this.clock, { method: 'POST', url: this.tokenEndpoint, nonce: this.authNonce });
      let res: Response;
      try {
        res = await this.fetchImpl(this.tokenEndpoint, {
          method: 'POST',
          headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json', DPoP: proof },
          body: new URLSearchParams({ client_id: this.config.clientId, ...params }).toString(),
        });
      } catch (e) {
        throw new NetworkError(e);
      }
      this.clock.observe(res.headers.get('date'));
      this.authNonce = res.headers.get('dpop-nonce') ?? this.authNonce;
      let body: Record<string, unknown> = {};
      try {
        body = (await res.json()) as Record<string, unknown>;
      } catch {
        // empty or not JSON
      }
      if (res.ok) return body;
      const error = typeof body.error === 'string' ? body.error : `http_${res.status}`;
      if (error === 'use_dpop_nonce' && attempt === 0) continue;
      throw new OAuthError(res.status, error, typeof body.error_description === 'string' ? body.error_description : undefined);
    }
  }

  /** Ends the sign-in: revokes the refresh token (best effort, single sign-out), then forgets the tokens and the key. */
  async signOut(): Promise<void> {
    const refreshToken = await this.storage.get(REFRESH_ITEM);
    if (refreshToken) {
      try {
        await this.fetchImpl(`${this.config.issuer}/oauth2/revoke`, {
          method: 'POST',
          headers: { 'content-type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            client_id: this.config.clientId,
            token: refreshToken,
            token_type_hint: 'refresh_token',
          }).toString(),
        });
      } catch {
        // offline: the refresh token dies within 12 h anyway, and the key is deleted below
      }
    }
    await this.clear();
  }

  private async clear() {
    this.access = null;
    this.key = null;
    this.authNonce = undefined;
    await this.storage.remove(REFRESH_ITEM);
    await deleteDeviceKey(this.storage);
    this.emit(false);
  }
}
