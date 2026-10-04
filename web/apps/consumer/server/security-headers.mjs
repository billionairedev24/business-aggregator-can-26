// Response headers the consumer server sets on every answer (S-104). The Studio and console get theirs from nginx
// (web/docker/security-headers.inc.template); this is the same policy for the SSR app, plus a Content-Security-Policy.
//
// script-src has no 'unsafe-inline' (S104-09, S-110 E7): every server-rendered page gets a fresh nonce
// (createNonce(), 128 random bits), which node-server.mjs hands to TanStack Start (request header NONCE_HEADER → the
// router's `ssr.nonce`, src/router.tsx); React and TanStack Start put it on every inline script they emit (the public
// configuration, the hydration state, the route scripts, React's streaming scripts). Scripts from files come from this
// site ('self') or the inventory below; no 'strict-dynamic' — nothing loads scripts that are neither. Answers that are
// not pages (files, robots.txt, the app-link files) get the same policy without a nonce: no inline script at all.
//
// S-110 (PCI DSS SAQ A, docs/compliance/pci/payment-page-scripts.md): the third-party script origins come from
// SCRIPT_INVENTORY — the payment-page script inventory — so no script origin can be allowed without being inventoried,
// and every violation is reported to POST /csp-report (report-uri + Reporting API), which logs one JSON line per
// violation (csp.violation) for the log pipeline to alert on. One policy, enforced; no second report-only policy.
import { randomBytes } from 'node:crypto';

/** Every third-party script origin the consumer web may load, with why (the payment-page script inventory). */
export const SCRIPT_INVENTORY = Object.freeze([
  Object.freeze({
    origin: 'https://js.stripe.com',
    script: 'Stripe.js v3 (https://js.stripe.com/v3), loaded on demand',
    why: 'Payment Element: card entry in Stripe’s iframe for cart, food checkout, booking deposit, quote acceptance and saved cards',
    integrity: 'not pinnable: Stripe serves v3 unversioned and forbids self-hosting or SRI; authorised by origin',
  }),
]);

export const CSP_REPORT_PATH = '/csp-report';
/** The request header that carries a page's nonce from node-server.mjs to the app (never accepted from a client). */
export const NONCE_HEADER = 'x-nl-csp-nonce';
export const CSP_REPORT_MAX_BODY = 16 * 1024;
const REPORT_GROUP = 'csp';

/** The auth origin the browser calls (JSON sign-in, form posts), from NL_AUTH_ORIGIN like the app's config. */
function origin(value, fallback) {
  try {
    return new URL(value ?? fallback).origin;
  } catch {
    return new URL(fallback).origin;
  }
}

/** A fresh nonce for one page: 128 random bits, base64 (CSP's nonce-source grammar). */
export const createNonce = () => randomBytes(16).toString('base64');

/** The Content-Security-Policy of the consumer site for this auth origin; `nonce` for a server-rendered page. */
export function contentSecurityPolicy(env = process.env, nonce = undefined) {
  const auth = origin(env.NL_AUTH_ORIGIN, 'http://localhost:9000');
  const scripts = SCRIPT_INVENTORY.map(s => s.origin).join(' ');
  return [
    "default-src 'self'",
    `script-src 'self'${nonce ? ` 'nonce-${nonce}'` : ''} ${scripts}`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob: https:",
    `connect-src 'self' ${auth} https://api.stripe.com`,
    'frame-src https://js.stripe.com https://hooks.stripe.com',
    `form-action 'self' ${auth}`,
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
    `report-uri ${CSP_REPORT_PATH}`,
    `report-to ${REPORT_GROUP}`,
  ].join('; ');
}

/** Every answer's security headers (lower-case names, as Node writes them); `nonce` for a server-rendered page. */
export function securityHeaders(env = process.env, nonce = undefined) {
  return {
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'strict-origin-when-cross-origin',
    'x-frame-options': 'DENY',
    'permissions-policy': 'camera=(), microphone=(), payment=(self), geolocation=(self)',
    'cross-origin-opener-policy': 'same-origin-allow-popups',
    'content-security-policy': contentSecurityPolicy(env, nonce),
    'reporting-endpoints': `${REPORT_GROUP}="${CSP_REPORT_PATH}"`,
  };
}

export const isCspReportPath = pathname => pathname === CSP_REPORT_PATH;

/** Origin and path of a reported URL — never its query or fragment (they can carry tokens or personal data). */
function trimUrl(value) {
  if (typeof value !== 'string' || value === '') return undefined;
  if (!/^[a-z][a-z0-9+.-]*:/i.test(value)) return value.slice(0, 40); // 'inline', 'eval', 'self'
  try {
    const url = new URL(value);
    return url.protocol.startsWith('http') ? `${url.origin}${url.pathname}` : url.protocol;
  } catch {
    return undefined;
  }
}

/**
 * One log line per violation from a report body (`application/csp-report` — one report — or `application/reports+json`
 * — an array). Returns the lines; an unreadable body gives none.
 */
export function cspReportLines(body) {
  if (typeof body !== 'string' || body.length === 0 || body.length > CSP_REPORT_MAX_BODY) return [];
  let parsed;
  try { parsed = JSON.parse(body); } catch { return []; }
  const reports = Array.isArray(parsed) ? parsed.filter(r => r?.type === 'csp-violation').map(r => r.body) : [parsed?.['csp-report']];
  return reports.filter(r => r && typeof r === 'object').map(r => JSON.stringify({
    'event.dataset': 'csp.violation',
    message: 'Content-Security-Policy violation',
    'csp.directive': String(r['effective-directive'] ?? r.effectiveDirective ?? r['violated-directive'] ?? '').slice(0, 60),
    'csp.blocked': trimUrl(r['blocked-uri'] ?? r.blockedURL),
    'csp.document': trimUrl(r['document-uri'] ?? r.documentURL),
    'csp.source': trimUrl(r['source-file'] ?? r.sourceFile),
    'csp.disposition': String(r.disposition ?? 'enforce').slice(0, 10),
  }));
}

/**
 * The POST /csp-report handler's core: logs each violation with `log`, at most `perMinute` lines a minute (a broken
 * extension on many browsers must not flood the logs). Returns the HTTP status to answer (204, or 413 when too big).
 */
export function createCspReporter({ log = line => console.log(line), perMinute = 300, now = () => Date.now() } = {}) {
  let windowStart = 0;
  let count = 0;
  return body => {
    if (typeof body === 'string' && body.length > CSP_REPORT_MAX_BODY) return 413;
    const t = now();
    if (t - windowStart >= 60_000) { windowStart = t; count = 0; }
    for (const line of cspReportLines(body)) {
      if (count >= perMinute) break;
      count += 1;
      log(line);
    }
    return 204;
  };
}
