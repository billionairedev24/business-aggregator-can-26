import { describe, expect, it, vi } from 'vitest';
import { brand } from '@northline/tokens';
import {
  devHints, ERROR_MESSAGES, errorJson, errorKind, errorLocale, errorReference, errorResponseParts, proxyErrors, renderErrorPage, wantsJson,
} from './error-page.mjs';

const refused = () => new TypeError('fetch failed', { cause: Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:8081'), { code: 'ECONNREFUSED' }) });
const req = (url: string, headers: Record<string, string> = {}) => ({ url, method: 'GET', headers: new Headers(headers) });

describe('the static error page', () => {
  it('negotiates: HTML for pages, JSON for /api, /bff and callers that ask for JSON only', () => {
    expect(wantsJson({ path: '/', accept: 'text/html,application/xhtml+xml,*/*;q=0.8' })).toBe(false);
    expect(wantsJson({ path: '/', accept: '*/*' })).toBe(false);
    expect(wantsJson({ path: '/', accept: 'application/json' })).toBe(true);
    expect(wantsJson({ path: '/', accept: 'text/html, application/json' })).toBe(false);
    expect(wantsJson({ path: '/api/v1/geo/regions', accept: 'text/html' })).toBe(true);
    expect(wantsJson({ path: '/bff/session' })).toBe(true);
    expect(wantsJson({ path: '/apiary' })).toBe(false);

    const log = vi.fn();
    const json = errorResponseParts({ status: 503, error: refused(), request: req('/api/v1/x', { accept: 'application/json' }), log });
    expect(json.headers['content-type']).toMatch(/^application\/json/);
    expect(JSON.parse(json.body)).toEqual({ status: 503, error: 'service_unavailable', ref: json.ref });
    const html = errorResponseParts({ status: 503, error: refused(), request: req('/services', { accept: 'text/html' }), log });
    expect(html.headers['content-type']).toMatch(/^text\/html/);
    expect(html.body).toMatch(/^<!DOCTYPE html>/);
    expect(html.headers['cache-control']).toBe('no-store');
  });

  it('keeps the status and says what it means: 404, 502/503/504 (unreachable), 500', () => {
    expect([404, 500, 502, 503, 504, 400].map(errorKind)).toEqual(['notFound', 'error', 'unreachable', 'unreachable', 'unreachable', 'error']);
    expect(renderErrorPage({ status: 503, ref: 'ABCD-EFGH' })).toContain(ERROR_MESSAGES.en.unreachableSiteTitle);
    expect(renderErrorPage({ status: 503, audience: 'app' })).toContain(`We can’t reach ${brand.name} right now.`);
    expect(renderErrorPage({ status: 404 })).toContain(ERROR_MESSAGES.en.notFoundTitle);
    const page = renderErrorPage({ status: 500, ref: 'ABCD-EFGH', url: '/shop/bakery?lang=en' });
    expect(page).toContain(ERROR_MESSAGES.en.errorTitle);
    expect(page).toContain('Error 500');
    expect(page).toContain('href="/shop/bakery?lang=en"'); // Try again: the same address
    expect(page).toContain('>Go to home<');
    expect(page).toContain('Reference ABCD-EFGH');
    expect(page).toContain(`mailto:${brand.supportEmail}`);
    expect(errorJson(504, 'X')).toBe('{"status":504,"error":"gateway_timeout","ref":"X"}');
  });

  it('is bilingual: ?lang=, then the nl.locale cookie, then Accept-Language; a malformed cookie is ignored', () => {
    expect(errorLocale({ url: '/?lang=fr', cookie: 'nl.locale=en' })).toBe('fr');
    expect(errorLocale({ url: '/', cookie: 'a=1; nl.locale=fr' })).toBe('fr');
    expect(errorLocale({ url: '/', cookie: 'nl.locale=%E0%A4%A', acceptLanguage: 'fr-CA,fr;q=0.9,en;q=0.8' })).toBe('fr');
    expect(errorLocale({ url: '%%', acceptLanguage: 'en-CA,fr;q=0.5' })).toBe('en');
    const fr = errorResponseParts({ status: 503, request: req('/', { 'accept-language': 'fr-CA' }), log: () => {} });
    expect(fr.body).toContain('<html lang="fr-CA">');
    expect(fr.body).toContain(ERROR_MESSAGES.fr.unreachableSiteTitle);
    expect(fr.headers['content-language']).toBe('fr-CA');
  });

  it('French and English have the same messages, none left in English', () => {
    expect(Object.keys(ERROR_MESSAGES.fr).sort()).toEqual(Object.keys(ERROR_MESSAGES.en).sort());
    for (const key of Object.keys(ERROR_MESSAGES.en)) if (key !== 'pageTitle') expect(ERROR_MESSAGES.fr[key], key).not.toBe(ERROR_MESSAGES.en[key]);
  });

  it('shows name, message, stack and hints in development only', () => {
    const error = refused();
    const dev = errorResponseParts({ status: 503, error, request: req('/'), dev: true, log: () => {} });
    expect(dev.body).toContain('Developer details');
    expect(dev.body).toContain('TypeError');
    expect(dev.body).toContain('ECONNREFUSED');
    expect(dev.body).toContain('make up SERVICES=&quot;auth api bff-consumer consumer&quot;');
    expect(dev.body).toContain('at '); // the stack
    const prod = errorResponseParts({ status: 503, error, request: req('/'), dev: false, log: () => {} });
    expect(prod.body).not.toContain('Developer details');
    expect(prod.body).not.toContain('ECONNREFUSED');
    expect(prod.body).not.toContain(error.stack!.split('\n')[1]!.trim());
  });

  it('names the usual local causes', () => {
    expect(devHints(refused()).join(' ')).toMatch(/consumer-bff on :8081 is not answering/);
    expect(devHints(new Error("Cannot find module '@northline/media' imported from /web/packages/ui/src/Photo.tsx")).join(' ')).toMatch(/A workspace package is missing \(@northline\/media\) — run pnpm install in web\/ \(or make dev again\), then restart/);
    expect(devHints(new Error("Failed to resolve import './gone' from src/x.tsx")).join(' ')).toMatch(/pnpm install in web\/ \(or make dev again\), then restart the dev server/);
    expect(devHints(Object.assign(new Error('Service Unavailable'), { status: 503 })).join(' ')).toMatch(/api behind it/);
    expect(devHints(new Error('anything'))).toHaveLength(1);
  });

  it('logs one JSON line with the reference, without the query string or headers', () => {
    const log = vi.fn();
    const answer = errorResponseParts({ status: 500, error: new Error('boom'), request: req('/search?q=amara@example.ca', { cookie: 'SESSION=secret' }), log });
    expect(log).toHaveBeenCalledOnce();
    const line = JSON.parse(log.mock.calls[0]![0] as string);
    expect(line).toMatchObject({ level: 'error', event: 'web.request_failed', ref: answer.ref, status: 500, method: 'GET', path: '/search', error: { name: 'Error', message: 'boom' } });
    expect(line.error.stack).toContain('boom');
    expect(JSON.stringify(line)).not.toMatch(/amara|secret/);
    expect(answer.headers['x-nl-error-ref']).toBe(answer.ref);
  });

  it('references are short and distinct', () => {
    const refs = new Set(Array.from({ length: 200 }, errorReference));
    expect(refs.size).toBe(200);
    for (const r of refs) expect(r).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  });

  it('escapes what it shows and puts the nonce on the retry script', () => {
    const page = renderErrorPage({ status: 503, url: '/"><script>x</script>', nonce: 'abc123', detail: { name: '<b>', message: '<img src=x>', stack: '', hints: [] } });
    expect(page).not.toContain('<script>x</script>');
    expect(page).not.toContain('<img src=x>');
    expect(page).toContain('<script nonce="abc123">');
    expect(renderErrorPage({ status: 500 })).not.toContain('<script');
  });

  it('the dev proxy answers 502 with the page (or JSON) when the BFF is down, instead of an empty 500', () => {
    let onError: ((e: Error, req: unknown, res: unknown) => void) | undefined;
    proxyErrors({ service: 'consumer-bff' })({ on: (_: string, cb: never) => { onError = cb; } }, { target: 'http://localhost:8081' });
    const res = { headersSent: false, writableEnded: false, writeHead: vi.fn(), end: vi.fn() };
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    onError!(Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' }), { url: '/oauth2/authorization/northline', method: 'GET', headers: { accept: 'text/html' } }, res);
    expect(res.writeHead).toHaveBeenCalledWith(502, expect.objectContaining({ 'content-type': 'text/html; charset=utf-8' }));
    expect(res.end.mock.calls[0]![0]).toContain('consumer-bff at http://localhost:8081 is not answering');
    const api = { ...res, writeHead: vi.fn(), end: vi.fn() };
    onError!(new Error('x'), { url: '/bff/session', method: 'GET', headers: {} }, api);
    expect(api.writeHead).toHaveBeenCalledWith(502, expect.objectContaining({ 'content-type': 'application/json; charset=utf-8' }));
    spy.mockRestore();
  });
});
