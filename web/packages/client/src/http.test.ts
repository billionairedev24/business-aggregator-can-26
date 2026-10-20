import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, failureKind, http, setHttpBase, setRequestLocale, traceparent, ValidationError, xsrfToken } from './http';

afterEach(() => { vi.unstubAllGlobals(); setHttpBase(''); setRequestLocale(undefined); });

const respond = (status: number, body?: unknown) =>
  vi.fn(async () => new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));

describe('http', () => {
  it('maps 422 to a ValidationError with the first message per field', async () => {
    vi.stubGlobal('fetch', respond(422, { errors: [{ field: 'phone', rule: 'required', message: 'Enter a mobile number.' }, { field: 'phone', rule: 'format', message: 'second' }] }));
    const err = await http('/api/v1/x', { method: 'POST', body: {} }).catch(e => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as ValidationError).byField()).toEqual({ phone: 'Enter a mobile number.' });
  });

  it('asks for the UI language, unless the call names its own (S-40)', async () => {
    const fetch = respond(200, { ok: true });
    vi.stubGlobal('fetch', fetch);
    await http('/api/v1/x');
    setRequestLocale('fr');
    await http('/api/v1/x');
    await http('/api/v1/x', { headers: { 'Accept-Language': 'en-CA' } });
    setRequestLocale('en');
    await http('/api/v1/x');
    const sent = fetch.mock.calls.map(c => ((c as unknown[])[1] as RequestInit).headers as Record<string, string>);
    expect(sent.map(h => h['accept-language'] ?? h['Accept-Language'])).toEqual([undefined, 'fr-CA', 'en-CA', 'en-CA']);
  });

  it('maps other failures to ApiError with the problem detail', async () => {
    vi.stubGlobal('fetch', respond(409, { detail: 'Slot taken' }));
    const err = await http('/api/v1/x').catch(e => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, message: 'Slot taken' });
  });

  it('prefixes same-origin paths with the server-side base, not absolute URLs', async () => {
    const fetch = respond(200, { ok: true });
    vi.stubGlobal('fetch', fetch);
    setHttpBase('http://northline-consumer-bff:8081/');
    await http('/api/v1/categories');
    await http('https://auth.example/api/auth/session');
    expect(fetch.mock.calls.map(c => (c as unknown[])[0])).toEqual(['http://northline-consumer-bff:8081/api/v1/categories', 'https://auth.example/api/auth/session']);
  });

  it('has no CSRF token where there is no document (server-side rendering)', () => {
    expect(typeof document).toBe('undefined');
    expect(xsrfToken()).toBeUndefined();
  });

  it('starts a W3C trace for each call to the BFF, never for another origin (S-111)', async () => {
    const fetch = respond(200, { ok: true });
    vi.stubGlobal('fetch', fetch);
    await http('/api/v1/categories');
    await http('/api/v1/categories');
    await http('https://auth.example/api/auth/session');
    const sent = fetch.mock.calls.map(c => ((c as unknown[])[1] as RequestInit).headers as Record<string, string>);
    const [first, second, other] = sent.map(h => h?.traceparent);
    expect(first).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    expect(second).not.toBe(first);
    expect(other).toBeUndefined();
  });

  it('makes a trace id that is never all zeros and a fresh one each time', () => {
    const ids = new Set(Array.from({ length: 50 }, () => traceparent().split('-')[1]));
    expect(ids.size).toBe(50);
    expect(ids.has('0'.repeat(32))).toBe(false);
  });
});

describe('failureKind (the error screens)', () => {
  it('tells the BFF or the api not answering from a missing page, a refusal, a new deploy and a bug', () => {
    expect(failureKind(new TypeError('fetch failed'))).toBe('unreachable'); // Node (server-side rendering)
    expect(failureKind(new TypeError('Failed to fetch'))).toBe('unreachable'); // Chrome
    expect(failureKind(new TypeError('NetworkError when attempting to fetch resource.'))).toBe('unreachable'); // Firefox
    expect(failureKind(new TypeError('Load failed'))).toBe('unreachable'); // Safari
    expect(failureKind(new DOMException('The operation timed out.', 'TimeoutError'))).toBe('unreachable');
    expect(failureKind(new ApiError(502, 'Bad Gateway'))).toBe('unreachable');
    expect(failureKind(new ApiError(500, 'Internal Server Error'))).toBe('unreachable');
    expect(failureKind(Object.assign(new Error('[{"code":"invalid_type"}]'), { name: 'ZodError' }))).toBe('unreachable');
    expect(failureKind(new SyntaxError('Unexpected token < in JSON at position 0'))).toBe('unreachable');
    expect(failureKind(new ApiError(404, 'x'))).toBe('notFound');
    expect(failureKind(new ApiError(403, 'x'))).toBe('forbidden');
    expect(failureKind(new ApiError(401, 'x'))).toBe('unauthorized');
    expect(failureKind(new ApiError(409, 'x'))).toBe('error');
    expect(failureKind(new TypeError('Failed to fetch dynamically imported module: https://x/assets/a.js'))).toBe('updated');
    expect(failureKind(new TypeError("Cannot read properties of undefined (reading 'id')"))).toBe('error');
    expect(failureKind('nope')).toBe('error');
  });
});

describe('the server-side timeout', () => {
  it('a BFF that never answers fails the call after timeoutMs instead of holding the page', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => new Promise((_, reject) => init.signal!.addEventListener('abort', () => reject(init.signal!.reason)))));
    setHttpBase('http://bff.test', { timeoutMs: 1000 });
    const pending = http('/api/v1/x').catch(e => e);
    await vi.advanceTimersByTimeAsync(1001);
    const err = await pending;
    expect(err.name).toBe('TimeoutError');
    expect(failureKind(err)).toBe('unreachable');
    vi.useRealTimers();
  });

  it('no timeout unless set, and none for another origin', async () => {
    const fetch = respond(200, {});
    vi.stubGlobal('fetch', fetch);
    await http('/api/v1/x');
    setHttpBase('http://bff.test', { timeoutMs: 1000 });
    await http('https://elsewhere.test/x');
    expect(fetch.mock.calls.map(c => ((c as unknown[])[1] as RequestInit).signal)).toEqual([undefined, undefined]);
  });
});
