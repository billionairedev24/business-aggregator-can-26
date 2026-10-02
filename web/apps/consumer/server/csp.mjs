// Content-Security-Policy of the consumer web (S-110, PCI DSS SAQ A — docs/compliance/pci/payment-page-scripts.md).
// Card entry happens only in Stripe's Payment Element (an iframe from js.stripe.com), but the pages that host it must
// not let another script in: that is the SAQ A eligibility criterion that replaced requirements 6.4.3 and 11.6.1.
//
// The policy is sent as Content-Security-Policy-Report-Only for now: TanStack Start's server rendering writes inline
// hydration scripts without a nonce, so an enforced script-src would need 'unsafe-inline' anyway; what the report-only
// policy buys is detection — any script, frame or connection from an origin outside the inventory below is reported
// to POST /csp-report and logged as one JSON line (csp.violation) the log pipeline keeps and alerts on.
// Enforcing it (with nonces) is the follow-up listed in saq-a.md.

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
const REPORT_GROUP = 'csp';

/** The consumer web's policy. `authOrigin` is northline-auth (sign-in JSON API and form posts). */
export function contentSecurityPolicy({ authOrigin } = {}) {
  const auth = authOrigin ? ` ${new URL(authOrigin).origin}` : '';
  const scripts = SCRIPT_INVENTORY.map(s => s.origin).join(' ');
  return [
    "default-src 'self'",
    `script-src 'self' 'unsafe-inline' ${scripts}`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob: https:",
    `connect-src 'self'${auth} https://api.stripe.com https://*.stripe.com`,
    'frame-src https://js.stripe.com https://hooks.stripe.com https://*.stripe.com',
    `form-action 'self'${auth}`,
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
    `report-uri ${CSP_REPORT_PATH}`,
    `report-to ${REPORT_GROUP}`,
  ].join('; ');
}

/** Headers for every answer: the report-only policy and where browsers send reports (Reporting API). */
export function cspHeaders(options) {
  return {
    'content-security-policy-report-only': contentSecurityPolicy(options),
    'reporting-endpoints': `${REPORT_GROUP}="${CSP_REPORT_PATH}"`,
  };
}

export const isCspReportPath = pathname => pathname === CSP_REPORT_PATH;

const MAX_BODY = 16 * 1024;

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
  if (typeof body !== 'string' || body.length === 0 || body.length > MAX_BODY) return [];
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
    'csp.disposition': String(r.disposition ?? 'report').slice(0, 10),
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
    if (typeof body === 'string' && body.length > MAX_BODY) return 413;
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

export const CSP_REPORT_MAX_BODY = MAX_BODY;
