import { AsyncLocalStorage } from 'node:async_hooks';
import { failureKind } from '@northline/client';
import { errorReference, errorResponseParts, logError } from '@northline/ui/error-page';

/*
 * The consumer's global server error handler (src/server.ts wraps TanStack Start's handler with it). Whatever escapes
 * the router — a module that fails to import (a stale install: the owner's raw `{"status":500,"unhandled":true,
 * "message":"HTTPError"}`), getRouter() throwing, a render error outside every Suspense boundary, an h3 HTTPError —
 * becomes the branded error page (@northline/ui/error-page) with its status kept, a reference that is also in one
 * structured log line, and in development the error itself with the usual causes. JSON only for callers that ask.
 */

type Handler = (request: Request, opts?: never) => Promise<Response> | Response;
interface Slot { error?: unknown }
const slots = new AsyncLocalStorage<Slot>();

/** h3's HTTPError, as its toResponse() logs it before answering JSON (prepareResponse → console.error(error)). */
const isH3Error = (v: unknown): v is Error & { status: number; cause?: unknown } =>
  v instanceof Error && v.name === 'HTTPError' && typeof (v as { status?: unknown }).status === 'number';

const g = globalThis as { __nlConsoleError?: typeof console.error };
/** The console.error before this module captured h3's (the log line goes there). */
const originalError = (): typeof console.error => g.__nlConsoleError ?? console.error;

/**
 * h3 answers an error that escapes TanStack Start with JSON and logs it with console.error; its Response carries
 * nothing of the error. While a request runs inside {@link withErrorPages}, that log call hands the error to the
 * request instead (one log line, ours, with the reference); everything else is logged as before. Installed once
 * (Vite re-runs this module on every change in development).
 */
export function captureH3Errors() {
  if (g.__nlConsoleError) return;
  const original = console.error.bind(console);
  g.__nlConsoleError = original;
  console.error = (...args: unknown[]) => {
    const slot = slots.getStore();
    if (slot && args.length === 1 && isH3Error(args[0])) { slot.error = args[0].cause ?? args[0]; return; }
    original(...args);
  };
}

/** A failed request's status: an HTTP error's own (404, 502…); an upstream failure 503 (504 kept); anything else 500. */
export function statusOf(error: unknown): number {
  const status = (error as { status?: unknown } | null)?.status;
  const kind = failureKind(error);
  if (kind === 'unreachable') return status === 502 || status === 504 ? status : 503;
  if (kind === 'notFound') return 404;
  return typeof status === 'number' && status >= 400 && status <= 599 ? status : 500;
}

/** h3's own error body (`{ status, unhandled, message }`), which nothing else in this app answers. */
async function h3ErrorBody(response: Response): Promise<{ status: number; message?: string } | null> {
  if (response.status < 400 || !response.headers.get('content-type')?.includes('application/json')) return null;
  const body = await response.clone().json().catch(() => null) as { status?: unknown; unhandled?: unknown; message?: unknown; error?: unknown } | null;
  if (!body || typeof body.status !== 'number' || 'error' in body) return null; // TanStack's own 406 has `error`
  return 'unhandled' in body || typeof body.message === 'string' ? { status: body.status, message: typeof body.message === 'string' ? body.message : undefined } : null;
}

export interface ErrorPageOptions { dev: boolean; supportEmail?: string | null; log?: (line: string) => void }

/** The branded answer for `request`: the page (or JSON), the status, no cache, the reference; logged once. */
export function errorPage(request: Request, status: number, error: unknown, opts: ErrorPageOptions): Response {
  const answer = errorResponseParts({
    status, error, request, dev: opts.dev, supportEmail: opts.supportEmail, log: opts.log ?? originalError(), source: 'consumer-ssr',
    // node-server.mjs chose this page's nonce and puts it in the policy (the retry script needs it)
    nonce: request.headers.get('x-nl-csp-nonce') ?? undefined,
  });
  return new Response(request.method === 'HEAD' ? null : answer.body, { status: answer.status, headers: answer.headers });
}

/**
 * The server entry's fetch: TanStack Start's, with every escaping error turned into the branded page. Also answers,
 * before the app: `/api/*` and `/bff/*` that reached this server (they belong to the consumer-bff: JSON 404), and with
 * NL_ERROR_PREVIEW=1 `/__nl/error?status=503` — the page itself, for the a11y sweep and `make ui-shots`.
 */
export function withErrorPages(start: Handler, opts: ErrorPageOptions & { preview?: boolean }): Handler {
  captureH3Errors();
  return async (request, ctx) => {
    let path = '/';
    try { path = new URL(request.url).pathname; } catch { /* "/" */ }
    if (/^\/(api|bff)\//.test(path)) return errorPage(request, 404, undefined, opts);
    if (opts.preview && path === '/__nl/error') {
      const status = Number(new URL(request.url).searchParams.get('status')) || 500;
      return errorPage(request, status, opts.dev ? new Error('A preview of the error page (NL_ERROR_PREVIEW=1)') : undefined, { ...opts, log: () => {} });
    }
    const slot: Slot = {};
    let response: Response;
    try {
      response = await slots.run(slot, () => start(request, ctx));
    } catch (error) {
      // the browser went away (closed the tab, followed another link): nobody to answer, nothing went wrong
      if (request.signal?.aborted) return new Response(null, { status: 499 });
      return errorPage(request, statusOf(error), error, opts);
    }
    if (request.signal?.aborted && response.status >= 500) return new Response(null, { status: 499 });
    const h3 = await h3ErrorBody(response);
    if (!h3) return response;
    // the error itself (captured from h3's log call) says more than h3's status: a BFF that is down is a 503
    const status = slot.error !== undefined && statusOf(slot.error) !== 500 ? statusOf(slot.error) : h3.status;
    return errorPage(request, status, slot.error ?? new Error(h3.message ?? `HTTP ${h3.status}`), opts);
  };
}

interface RouterLike { state: { matches: { status: string; error?: unknown; routeId?: string }[] } }
type SsrResult = Response | { response: Response };

/**
 * After the router rendered a route error (its errorComponent inside the page shell, status 500): log each error once
 * with a reference, and answer 503 instead of 500 when every one of them is the BFF or the api not answering — the
 * page says "We can't reach the shop right now" and tries again in the browser.
 */
export function upstreamStatus<T extends SsrResult>(router: RouterLike, result: T, request: Request, opts: { log?: (line: string) => void } = {}): T {
  const response = result instanceof Response ? result : result.response;
  if (response.status !== 500) return result;
  const errors = router.state.matches.filter(m => m.status === 'error').map(m => m.error);
  if (!errors.length) return result;
  const ref = errorReference();
  for (const error of errors) logError({ ref, status: statusOf(error), method: request.method, url: request.url, error, source: 'consumer-ssr' }, opts.log ?? originalError());
  const headers = new Headers(response.headers);
  headers.set('x-nl-error-ref', ref);
  const status = errors.every(e => failureKind(e) === 'unreachable') ? 503 : 500;
  const replaced = new Response(response.body, { status, statusText: '', headers });
  return (result instanceof Response ? replaced : { ...result, response: replaced }) as T;
}
