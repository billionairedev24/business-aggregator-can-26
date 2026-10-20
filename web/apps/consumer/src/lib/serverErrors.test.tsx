// @vitest-environment node
// The consumer's server error handling (lib/serverErrors.ts, src/server.ts): what escapes TanStack Start becomes the
// branded page, and a server-side render with the consumer-bff down / answering 500 / a loader that throws renders the
// page shell with the right state and status. Node environment: the router renders as it does on the server.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRouteWithContext, createRoute, createRouter, Outlet } from '@tanstack/react-router';
import { createRequestHandler, defaultRenderHandler } from '@tanstack/react-router/ssr/server';
import { ApiError, http, setHttpBase } from '@northline/client';
import { I18nProvider } from '@northline/ui';
import { regionsQuery } from '../features/location/regions';
import { RouteError } from '../features/shell/RouteError';
import { NotFound } from '../features/shell/NotFound';
import { statusOf, upstreamStatus, withErrorPages } from './serverErrors';

afterEach(() => { vi.unstubAllGlobals(); setHttpBase(''); });

/** An error as h3 builds it for something that escaped TanStack Start (HTTPError, `unhandled`, the error as cause). */
function h3Error(cause: Error) {
  return Object.assign(Object.create(Error.prototype, { name: { get: () => 'HTTPError' } }), { message: cause.message, status: 500, unhandled: true, cause, stack: cause.stack }) as Error;
}
/** What h3's toResponse does with it: log it, answer its raw JSON. */
const h3Answer = (cause: Error) => async () => {
  console.error(h3Error(cause));
  return Response.json({ status: 500, unhandled: true, message: 'HTTPError' }, { status: 500 });
};
const page = (path = '/', headers: Record<string, string> = { accept: 'text/html,*/*;q=0.8' }) => new Request(`http://localhost:3000${path}`, { headers });

describe('withErrorPages: nothing leaves as raw JSON', () => {
  it('reproduces the owner\'s 500 (a workspace package not installed) and answers the branded page instead', async () => {
    const log = vi.fn();
    const missing = Object.assign(new Error("Cannot find module '@northline/media' imported from '/web/packages/ui/src/Photo.tsx'"), { code: 'ERR_MODULE_NOT_FOUND' });
    const fetch = withErrorPages(h3Answer(missing), { dev: true, log });
    const res = await fetch(page('/'));
    expect(res.status).toBe(500);
    expect(res.headers.get('content-type')).toMatch(/^text\/html/);
    const html = await res.text();
    expect(html).not.toContain('"unhandled":true');
    expect(html).toContain('Something went wrong on our side.');
    expect(html).toContain('Cannot find module &#39;@northline/media&#39;');
    expect(html).toContain('A workspace package is missing (@northline/media) — run pnpm install in web/ (or make dev again), then restart');
    // one log line, ours, with the reference the page shows (h3's own console.error was taken over)
    expect(log).toHaveBeenCalledOnce();
    const line = JSON.parse(log.mock.calls[0]![0] as string);
    expect(line).toMatchObject({ event: 'web.request_failed', status: 500, path: '/', ref: res.headers.get('x-nl-error-ref') });
    expect(line.error.message).toContain('@northline/media');
    expect(html).toContain(`Reference ${line.ref}`);
  });

  it('never shows the error in production; JSON only to callers that ask for it', async () => {
    const fetch = withErrorPages(h3Answer(new Error('secret internals')), { dev: false, log: () => {} });
    const html = await (await fetch(page('/'))).text();
    expect(html).toContain('Something went wrong on our side.');
    expect(html).not.toContain('secret internals');
    expect(html).not.toContain('Developer details');
    const json = await fetch(page('/', { accept: 'application/json' }));
    expect(json.status).toBe(500);
    expect(await json.json()).toEqual({ status: 500, error: 'internal_error', ref: json.headers.get('x-nl-error-ref') });
  });

  it('a thrown error keeps its meaning: the BFF down → 503, an HTTP error its status, else 500', async () => {
    const down = new TypeError('fetch failed', { cause: Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:8081'), { code: 'ECONNREFUSED' }) });
    const res = await withErrorPages(async () => { throw down; }, { dev: true, log: () => {} })(page('/services', { accept: 'text/html', 'accept-language': 'fr-CA,fr' }));
    expect(res.status).toBe(503);
    const html = await res.text();
    expect(html).toContain('<html lang="fr-CA">');
    expect(html).toContain('Impossible de joindre la boutique pour le moment.');
    expect(html).toContain('consumer-bff on :8081 is not answering');
    expect(statusOf(Object.assign(new Error('x'), { status: 404 }))).toBe(404);
    expect(statusOf(new ApiError(504, 'Gateway Timeout'))).toBe(504);
    expect(statusOf(new ApiError(500, 'Internal Server Error'))).toBe(503); // the BFF's 500: the shop can't be reached
    expect(statusOf(new Error('bug'))).toBe(500);
  });

  it('leaves the app\'s own answers alone (pages, 404 pages, TanStack\'s 406 JSON)', async () => {
    const ok = new Response('<!DOCTYPE html><html></html>', { status: 404, headers: { 'content-type': 'text/html' } });
    expect(await withErrorPages(async () => ok, { dev: false })(page('/nope'))).toBe(ok);
    const notHtml = Response.json({ error: 'Only HTML requests are supported here' }, { status: 406 });
    expect(await withErrorPages(async () => notHtml, { dev: false })(page('/', { accept: 'text/plain' }))).toBe(notHtml);
  });

  it('answers /api and /bff that reached it with JSON 404, the preview page when asked, and nothing to a gone browser', async () => {
    const start = vi.fn();
    const fetch = withErrorPages(start, { dev: false, preview: true, log: () => {} });
    const api = await fetch(page('/api/v1/geo/regions'));
    expect(api.status).toBe(404);
    expect((await api.json()).error).toBe('not_found');
    const preview = await fetch(page('/__nl/error?status=503'));
    expect(preview.status).toBe(503);
    expect(await preview.text()).toContain('We can’t reach the shop right now.');
    expect(start).not.toHaveBeenCalled();
    const gone = new AbortController();
    const req = new Request('http://localhost:3000/', { signal: gone.signal });
    gone.abort();
    const res = await withErrorPages(async () => { throw new Error('aborted'); }, { dev: false, log: () => { throw new Error('logged'); } })(req);
    expect(res.status).toBe(499);
  });
});

/** The consumer's shell as the server renders it: the root prefetches the regions; /services loads its catalogue. */
function ssr(path: string, loader: () => Promise<unknown>) {
  const createAppRouter = () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const root = createRootRouteWithContext<{ queryClient: QueryClient }>()({
      loader: ({ context }) => context.queryClient.prefetchQuery(regionsQuery('en')), // never throws
      component: () => <QueryClientProvider client={queryClient}><I18nProvider initial="en"><header>shell</header><main><Outlet /></main></I18nProvider></QueryClientProvider>,
    });
    const services = createRoute({ getParentRoute: () => root, path: '/services', loader, component: () => <h1>Services</h1> });
    const home = createRoute({ getParentRoute: () => root, path: '/', component: () => <h1>Home</h1> });
    return createRouter({
      routeTree: root.addChildren([home, services]), context: { queryClient }, history: createMemoryHistory({ initialEntries: [path] }),
      defaultErrorComponent: RouteError, defaultNotFoundComponent: NotFound,
    });
  };
  const request = new Request(`http://localhost:3000${path}`);
  return createRequestHandler({ request, createRouter: createAppRouter })(async ctx => upstreamStatus(ctx.router, await defaultRenderHandler(ctx), request, { log: () => {} }));
}

/** What the page shows (the hydration state in the scripts carries the error for the browser: start.ts's adapters). */
const visible = async (res: Response) => (await res.text()).replace(/<script[\s\S]*?<\/script>/g, '');

describe('server-side rendering when the consumer-bff fails', () => {
  const catalogue = () => http('/api/v1/public/services');
  setHttpBase('http://bff.test');

  it('BFF down: the shell renders with "We can\'t reach the shop right now", 503', async () => {
    setHttpBase('http://bff.test');
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    const res = await ssr('/services', catalogue);
    expect(res.status).toBe(503);
    const html = await visible(res);
    expect(html).toContain('shell');
    expect(html).toContain('We can’t reach the shop right now.');
    expect(html).toContain('Try again');
    expect(html).not.toContain('fetch failed');
    expect(res.headers.get('x-nl-error-ref')).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  });

  it('BFF answers 500 (or an HTML page instead of JSON): the same unreachable state, not the raw "Internal Server Error"', async () => {
    setHttpBase('http://bff.test');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"status":500,"error":"Internal Server Error"}', { status: 500, statusText: 'Internal Server Error' })));
    const res = await ssr('/services', catalogue);
    expect(res.status).toBe(503);
    const html = await visible(res);
    expect(html).toContain('We can’t reach the shop right now.');
    expect(html).not.toContain('Internal Server Error');
  });

  it('a loader that throws (a bug): "Something went wrong on our side", 500, never the message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    const res = await ssr('/services', async () => { throw new Error('Cannot read properties of undefined (reading "slug")'); });
    expect(res.status).toBe(500);
    const html = await visible(res);
    expect(html).toContain('Something went wrong on our side.');
    expect(html).not.toContain('reading');
  });

  it('the root\'s regions prefetch failing never fails the page', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    const res = await ssr('/', async () => null);
    expect(res.status).toBe(200);
    expect(await res.text()).toContain('<h1>Home</h1>');
  });
});

describe('errors in the hydration state (start.ts)', () => {
  it('an ApiError keeps its status; any other upstream failure arrives as a 503; a bug keeps no internals in production', async () => {
    const { apiErrorAdapter, upstreamErrorAdapter, internalErrorAdapter, internalErrorBody } = await import('../start');
    const back = apiErrorAdapter.fromSerializable(apiErrorAdapter.toSerializable(new ApiError(404, 'No such provider')));
    expect(back).toBeInstanceOf(ApiError);
    expect(back).toMatchObject({ status: 404, message: 'No such provider' });
    const zod = Object.assign(new Error('[{"code":"invalid_type"}]'), { name: 'ZodError' });
    expect(upstreamErrorAdapter.test(zod)).toBe(true);
    expect(upstreamErrorAdapter.test(new TypeError('fetch failed'))).toBe(true);
    expect(upstreamErrorAdapter.fromSerializable(upstreamErrorAdapter.toSerializable(zod))).toMatchObject({ status: 503 });
    const bug = new Error('Cannot read properties of undefined');
    expect(internalErrorAdapter.test(bug)).toBe(true);
    expect(internalErrorAdapter.test(zod) || internalErrorAdapter.test(new ApiError(500, 'x'))).toBe(false);
    expect(internalErrorBody(bug, false)).toEqual({ name: 'Error', message: '' });
  });
});
