import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, http, setHttpBase, ValidationError, xsrfToken } from './http';

afterEach(() => { vi.unstubAllGlobals(); setHttpBase(''); });

const respond = (status: number, body?: unknown) =>
  vi.fn(async () => new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));

describe('http', () => {
  it('maps 422 to a ValidationError with the first message per field', async () => {
    vi.stubGlobal('fetch', respond(422, { errors: [{ field: 'phone', rule: 'required', message: 'Enter a mobile number.' }, { field: 'phone', rule: 'format', message: 'second' }] }));
    const err = await http('/api/v1/x', { method: 'POST', body: {} }).catch(e => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as ValidationError).byField()).toEqual({ phone: 'Enter a mobile number.' });
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
});
