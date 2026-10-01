import { OAuthError, type DpopSession } from '../auth/session';
import { createProof } from '../dpop/proof';
import { ApiError, apiErrorOf, NetworkError, SignedOutError } from './errors';

export interface RequestOptions {
  /** A JSON body. */
  json?: unknown;
  /** A multipart body (proof photos). */
  form?: FormData;
  /** Sent as `Idempotency-Key`: the same for every retry of one action. */
  idempotencyKey?: string;
}

/** What an api call may answer with besides its body. */
export interface ApiAnswer<T> {
  status: number;
  body: T | null;
  headers: Headers;
}

/**
 * Calls `https://api.<zone>/api/v1/…` with the session's DPoP-bound token (docs/runbooks/mobile-auth.md § 5): every
 * request carries `Authorization: DPoP <token>` and a new proof with `ath`. A 401 refreshes once and retries with a
 * new proof. Errors: {@link ApiError} (an answer), {@link NetworkError} (none), {@link SignedOutError} (sign in again).
 */
export class ApiClient {
  constructor(
    /** `https://api.<zone>/api/v1` (no trailing slash). */
    readonly baseUrl: string,
    private readonly session: DpopSession,
    private readonly language: () => string = () => 'en',
    private readonly fetchImpl: typeof fetch = (...a) => fetch(...a),
  ) {}

  async call<T>(method: string, path: string, options: RequestOptions = {}): Promise<ApiAnswer<T>> {
    const url = `${this.baseUrl}${path}`;
    for (let attempt = 0; ; attempt++) {
      let token: string;
      try {
        token = attempt === 0 ? await this.session.accessToken() : await this.session.refresh();
      } catch (e) {
        if (e instanceof OAuthError) {
          // northline-auth's replay store down (503) or another server-side problem: try again later
          throw e.status >= 500 ? new NetworkError(e) : new SignedOutError(e.error);
        }
        throw e;
      }
      const proof = await createProof(this.session.deviceKey(), this.session.clock, { method, url, accessToken: token });
      const headers: Record<string, string> = {
        Authorization: `DPoP ${token}`,
        DPoP: proof,
        Accept: 'application/json',
        'Accept-Language': this.language(),
      };
      if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
      let body: string | FormData | undefined;
      if (options.json !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.json);
      } else if (options.form) {
        body = options.form; // fetch sets the multipart boundary
      }
      let res: Response;
      try {
        res = await this.fetchImpl(url, { method, headers, body });
      } catch (e) {
        throw new NetworkError(e);
      }
      this.session.clock.observe(res.headers.get('date'));
      if (res.status === 401 && attempt === 0) continue; // expired or refused token: refresh once
      if (res.status === 401) throw new SignedOutError('unauthorized');
      if (!res.ok) throw await apiErrorOf(res);
      if (res.status === 204) return { status: 204, body: null, headers: res.headers };
      const text = await res.text();
      return { status: res.status, body: text ? (JSON.parse(text) as T) : null, headers: res.headers };
    }
  }

  async get<T>(path: string): Promise<T | null> {
    return (await this.call<T>('GET', path)).body;
  }

  async post<T>(path: string, options: RequestOptions = {}): Promise<T | null> {
    return (await this.call<T>('POST', path, options)).body;
  }
}

export { ApiError };
