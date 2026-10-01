import type { z } from 'zod';

/**
 * The web apps' HTTP client (Studio, consumer web): same-origin calls through the app's BFF, which holds the session
 * (the browser never sees a token). Moved here from the Studio's `lib/http.ts` in S-45 so the consumer app shares it.
 */

/** One field error from the API — `422 { errors: [{ field, rule, message }] }` (docs/spec/validation-rules.md). */
export interface FieldError { field: string; rule: string; message: string }

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly body?: unknown) { super(message); this.name = 'ApiError'; }
}
export class ValidationError extends ApiError {
  constructor(readonly errors: FieldError[]) { super(422, errors.map(e => e.message).join(' ')); this.name = 'ValidationError'; }
  /** field → first message, for wiring into forms. */
  byField(): Record<string, string> { return Object.fromEntries([...this.errors].reverse().map(e => [e.field, e.message])); }
}
export const isUnauthorized = (e: unknown) => e instanceof ApiError && e.status === 401;
export const isNotFound = (e: unknown) => e instanceof ApiError && e.status === 404;

/**
 * The BFF's CSRF token, raw. The cloud names the cookie `__Host-XSRF-TOKEN` (S-20: a sibling subdomain can't plant a
 * `__Host-` cookie); locally it is `XSRF-TOKEN`. Sent back as `X-XSRF-TOKEN` — the BFF accepts it from that header only.
 * Undefined on the server (SSR sends no state-changing requests).
 */
export function xsrfToken(): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const cookies = document.cookie.split('; ');
  for (const name of ['__Host-XSRF-TOKEN', 'XSRF-TOKEN']) {
    const value = cookies.find(c => c.startsWith(`${name}=`))?.slice(name.length + 1);
    if (value) return decodeURIComponent(value);
  }
  return undefined;
}

let base = '';
/**
 * Origin prefixed to same-origin paths. Browsers leave it empty; the consumer app's server-side rendering sets the
 * in-cluster BFF (`NL_BFF_URL`) once at start-up, so loaders fetch public data through the BFF as a guest.
 */
export function setHttpBase(origin: string) { base = origin.replace(/\/$/, ''); }
const resolve = (path: string) => (path.startsWith('/') ? base + path : path);

/**
 * A new W3C trace context for one call (S-111, docs/runbooks/observability.md): `00-<trace id>-<parent id>-01`. The BFF
 * continues this trace through the api, the database and Kafka, so a request seen in the browser's network panel can
 * be found in the tracing backend by its trace id. The browser exports no spans itself; the flag only asks — each
 * service decides on the trace id with the same ratio (ConsistentSampling), so a caller can't force sampling.
 */
export function traceparent(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `00-${hex.slice(0, 32)}-${hex.slice(32)}-01`;
}

let language: string | undefined;
/**
 * The UI's language (S-40), sent as `Accept-Language` on every call so the api answers 422 messages (and 403/409
 * details) in it — the app's language switch, not the browser's, decides. `undefined` leaves the browser's header.
 */
export function setRequestLocale(locale: 'en' | 'fr' | undefined) {
  language = locale === 'fr' ? 'fr-CA' : locale === 'en' ? 'en-CA' : undefined;
}

export interface RequestOptions { method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'; body?: unknown; idempotencyKey?: string; signal?: AbortSignal; headers?: Record<string, string> }

/**
 * Same-origin fetch through the BFF (session cookie). Parses JSON, maps 422 to ValidationError, other failures to
 * ApiError, and validates the response with `schema` when given.
 */
export async function http<T = unknown>(path: string, opts: RequestOptions = {}, schema?: z.ZodType<T>): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined && !(opts.body instanceof FormData)) headers['content-type'] = 'application/json';
  if (method !== 'GET') { const t = xsrfToken(); if (t) headers['x-xsrf-token'] = t; }
  if (opts.idempotencyKey) headers['idempotency-key'] = opts.idempotencyKey;
  if (language && !Object.keys(headers).some(h => h.toLowerCase() === 'accept-language')) headers['accept-language'] = language;
  // Same-origin (the BFF) only: another origin's CORS policy may not allow the header.
  if (path.startsWith('/') && !headers.traceparent) headers.traceparent = traceparent();
  const res = await fetch(resolve(path), { method, headers, credentials: 'include', signal: opts.signal, body: opts.body === undefined ? undefined : opts.body instanceof FormData ? opts.body : JSON.stringify(opts.body) });
  const text = await res.text();
  const data: unknown = text ? safeJson(text) : undefined;
  if (res.status === 422 && data && typeof data === 'object' && 'errors' in data) throw new ValidationError((data as { errors: FieldError[] }).errors);
  if (!res.ok) {
    const detail = data && typeof data === 'object' && 'detail' in data ? String((data as { detail: unknown }).detail) : res.statusText;
    throw new ApiError(res.status, detail || `Request failed (${res.status})`, data);
  }
  return schema ? schema.parse(data) : (data as T);
}
const safeJson = (t: string): unknown => { try { return JSON.parse(t); } catch { return t; } };

/** Key for money-moving POSTs (Idempotency-Key header, kept 24 h server-side). */
export const newIdempotencyKey = () => crypto.randomUUID();
