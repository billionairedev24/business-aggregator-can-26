import { NetworkError } from '../api/errors';
import { OAuthError, type DpopSession, type PendingSignIn } from './session';

type Fetch = typeof fetch;

/**
 * Sign-in on the app's own screens (the consumer app's Journey A, S-98), ending in the same DPoP-bound tokens as the
 * system-browser flow. The app is a first-party client: it drives northline-auth's JSON sign-in API (the one the
 * consumer site's pages use) in its own HTTP session, then lets northline-auth issue the authorization code there:
 *
 * 1. {@link begin}: `GET /oauth2/authorize` (PKCE S256, the client's claimed https redirect) in the app's cookie
 *    session. Not signed in yet, so northline-auth keeps the request in that session and points at the sign-in page.
 * 2. The app's screens call `/api/auth/register…` or `/api/auth/sign-in…` in the same session; the answer that signs
 *    the person in carries `continueTo` = the kept authorization request (S-29's `AppAuthorizationResume`).
 * 3. {@link complete}: `GET continueTo`. Now signed in, northline-auth redirects to the redirect URI with `code` and
 *    `state`; fetch follows it, and the URL it lands on carries the code, which is exchanged with a DPoP proof as usual.
 *
 * Only a request to the issuer's own `/oauth2/authorize` for this client and this `state` is followed. The redirect URI
 * must be an https URL fetch can land on (the custom scheme can't be fetched): the consumer site serves a small page
 * there (web/apps/consumer/server/app-links.mjs). The session cookie stays in the platform's HTTP cookie store
 * (NSHTTPCookieStorage / the Android CookieManager), never in JS.
 */
export class AppSignIn {
  constructor(
    private readonly session: DpopSession,
    /** One of the client's registered https redirects (`https://<consumer host>/app/oauth2redirect`). */
    private readonly redirectUri: string,
    private readonly fetchImpl: Fetch = (...a) => fetch(...a),
  ) {}

  /** Step 1: a new authorization request, kept by northline-auth in the app's session. */
  async begin(): Promise<PendingSignIn> {
    const pending = this.session.beginSignIn({}, this.redirectUri);
    await this.get(pending.url);
    return pending;
  }

  /**
   * Step 3: follows `continueTo` (or, when the answer had none, the original request again — the session is signed in
   * now, so it answers with a code at once) and exchanges the code.
   */
  async complete(continueTo: string | null | undefined, pending: PendingSignIn): Promise<void> {
    const url = this.authorizeUrl(continueTo ?? pending.url, pending);
    const res = await this.get(url);
    const landed = res.url;
    if (!landed || !landed.startsWith(`${this.redirectUri}?`)) {
      throw new OAuthError(res.status || 400, 'no_redirect', 'northline-auth did not hand back an authorization code');
    }
    await this.session.completeSignIn(landed, pending);
  }

  /** The issuer's `/oauth2/authorize` with the query of `candidate`, if it is this client's request with this state. */
  private authorizeUrl(candidate: string, pending: PendingSignIn): string {
    let parsed: URL;
    try {
      parsed = new URL(candidate, this.session.config.issuer);
    } catch {
      throw new OAuthError(400, 'bad_continue');
    }
    const q = parsed.searchParams;
    if (
      !parsed.pathname.endsWith('/oauth2/authorize') ||
      q.get('client_id') !== this.session.config.clientId ||
      q.get('state') !== pending.state ||
      q.get('redirect_uri') !== this.redirectUri
    ) {
      throw new OAuthError(400, 'bad_continue', 'Not this sign-in’s authorization request');
    }
    return `${this.session.config.issuer}/oauth2/authorize${parsed.search}`;
  }

  private async get(url: string): Promise<Response> {
    try {
      return await this.fetchImpl(url, { method: 'GET', headers: { Accept: 'text/html' }, credentials: 'include' });
    } catch (e) {
      throw new NetworkError(e);
    }
  }
}
