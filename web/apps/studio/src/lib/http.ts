import type { z } from 'zod';

/** One field error from the API — `422 { errors: [{ field, rule, message }] }` (docs/spec/validation-rules.md). */
export interface FieldError { field: string; rule: string; message: string }

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly body?: unknown) { super(message); this.name = 'ApiError'; }
}
export class ValidationError extends ApiError {
  constructor(readonly errors: FieldError[]) { super(422, errors.map(e => e.message).join(' ')); this.name = 'ValidationError'; }
  /** field → first message, for wiring into forms. */
  byField(): Record<string, string> { return Object.fromEntries(this.errors.reverse().map(e => [e.field, e.message])); }
}
export const isUnauthorized = (e: unknown) => e instanceof ApiError && e.status === 401;

function xsrf(): string | undefined {
  return document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length);
}

export interface RequestOptions { method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'; body?: unknown; idempotencyKey?: string; signal?: AbortSignal; headers?: Record<string, string> }

/**
 * Same-origin fetch through the studio BFF (session cookie; the browser never holds tokens).
 * Parses JSON, maps 422 to ValidationError, other failures to ApiError, and validates the response with `schema` when given.
 */
export async function http<T = unknown>(path: string, opts: RequestOptions = {}, schema?: z.ZodType<T>): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined && !(opts.body instanceof FormData)) headers['content-type'] = 'application/json';
  if (method !== 'GET') { const t = xsrf(); if (t) headers['x-xsrf-token'] = decodeURIComponent(t); }
  if (opts.idempotencyKey) headers['idempotency-key'] = opts.idempotencyKey;
  const res = await fetch(path, { method, headers, credentials: 'include', signal: opts.signal, body: opts.body === undefined ? undefined : opts.body instanceof FormData ? opts.body : JSON.stringify(opts.body) });
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
