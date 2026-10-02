import { OAuthError, type DpopSession } from '../auth/session';
import { createProof } from '../dpop/proof';
import { ApiError, apiErrorOf, NetworkError, SignedOutError } from './errors';

/**
 * Who a call is made as:
 * - `required` (default): the signed-in person, DPoP-bound; no sign-in → {@link SignedOutError}.
 * - `optional`: the person when there is a sign-in, else anonymously — the public reads a guest may make too
 *   (`/api/v1/public/**`, `/api/v1/geo/**`, `/api/v1/search/**`). A sign-in that ended on the way falls back to anonymous.
 * - `none`: always anonymous.
 */
export type CallAuth = 'required' | 'optional' | 'none';

export interface RequestOptions {
  /** A JSON body. */
  json?: unknown;
  /** A multipart body (proof photos). */
  form?: FormData;
  /** Sent as `Idempotency-Key`: the same for every retry of one action. */
  idempotencyKey?: string;
  /** Default `required`. */
  auth?: CallAuth;
  /** Extra headers (`X-Step-Up`, …). Authorization, DPoP and Accept-Language are the client's. */
  headers?: Record<string, string>;
}

/** What an api call may answer with besides its body. */
export interface ApiAnswer<T> {
  status: number;
  body: T | null;
  headers: Headers;
}

/** A request that got no answer within this long is given up as a {@link NetworkError} (flaky mobile networks). */
export const DEFAULT_TIMEOUT_MS = 20_000;

/**
 * Calls `https://api.<zone>/api/v1/…` with the session's DPoP-bound token (docs/runbooks/mobile-auth.md § 5): every
 * request carries `Authorization: DPoP <token>` and a new proof with `ath`. A 401 refreshes once and retries with a
 * new proof. Errors: {@link ApiError} (an answer), {@link NetworkError} (none, or none in time), {@link SignedOutError}
 * (sign in again). Public reads pass `auth: 'optional'` so guests can make them.
 */
export class ApiClient {
  constructor(
    /** `https://api.<zone>/api/v1` (no trailing slash). */
    readonly baseUrl: string,
    private readonly session: DpopSession,
    private readonly language: () => string = () => 'en',
    private readonly fetchImpl: typeof fetch = (...a) => fetch(...a),
    private readonly timeoutMs: number = DEFAULT_TIMEOUT_MS,
    /** Headers on every call (the consumer app's `X-Northline-Guest`); a call's own `headers` win. */
    private readonly defaultHeaders: () => Record<string, string> = () => ({}),
  ) {}

  async call<T>(method: string, path: string, options: RequestOptions = {}): Promise<ApiAnswer<T>> {
    const url = `${this.baseUrl}${path}`;
    const auth = options.auth ?? 'required';
    let anonymous = auth === 'none' || (auth === 'optional' && !(await this.session.restore()));
    for (let attempt = 0; ; attempt++) {
      const headers: Record<string, string> = {
        ...this.defaultHeaders(),
        ...(options.headers ?? {}),
        Accept: 'application/json',
        'Accept-Language': this.language(),
      };
      if (!anonymous) {
        let token: string;
        try {
          token = attempt === 0 ? await this.session.accessToken() : await this.session.refresh();
        } catch (e) {
          const ended = e instanceof SignedOutError || (e instanceof OAuthError && e.status < 500);
          if (ended && auth === 'optional') {
            anonymous = true;
            continue;
          }
          if (e instanceof OAuthError) {
            // northline-auth's replay store down (503) or another server-side problem: try again later
            throw e.status >= 500 ? new NetworkError(e) : new SignedOutError(e.error);
          }
          throw e;
        }
        headers.Authorization = `DPoP ${token}`;
        headers.DPoP = await createProof(this.session.deviceKey(), this.session.clock, { method, url, accessToken: token });
      }
      if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
      let body: string | FormData | undefined;
      if (options.json !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.json);
      } else if (options.form) {
        body = options.form; // fetch sets the multipart boundary
      }
      const res = await this.send(url, { method, headers, body });
      this.session.clock.observe(res.headers.get('date'));
      if (res.status === 401 && !anonymous && attempt === 0) continue; // expired or refused token: refresh once
      if (res.status === 401) throw new SignedOutError('unauthorized');
      if (!res.ok) throw await apiErrorOf(res);
      if (res.status === 204) return { status: 204, body: null, headers: res.headers };
      const text = await res.text();
      return { status: res.status, body: text ? (JSON.parse(text) as T) : null, headers: res.headers };
    }
  }

  private async send(url: string, init: RequestInit): Promise<Response> {
    const controller = typeof AbortController === 'undefined' ? null : new AbortController();
    const timer = controller ? setTimeout(() => controller.abort(), this.timeoutMs) : null;
    try {
      return await this.fetchImpl(url, controller ? { ...init, signal: controller.signal } : init);
    } catch (e) {
      throw new NetworkError(e);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async get<T>(path: string, options: Omit<RequestOptions, 'json' | 'form'> = {}): Promise<T | null> {
    return (await this.call<T>('GET', path, options)).body;
  }

  async post<T>(path: string, options: RequestOptions = {}): Promise<T | null> {
    return (await this.call<T>('POST', path, options)).body;
  }

  async put<T>(path: string, options: RequestOptions = {}): Promise<T | null> {
    return (await this.call<T>('PUT', path, options)).body;
  }

  async patch<T>(path: string, options: RequestOptions = {}): Promise<T | null> {
    return (await this.call<T>('PATCH', path, options)).body;
  }

  async delete<T>(path: string, options: RequestOptions = {}): Promise<T | null> {
    return (await this.call<T>('DELETE', path, options)).body;
  }
}

export { ApiError };
