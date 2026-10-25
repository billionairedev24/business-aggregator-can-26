// The brand's error page when the React app cannot answer (plain HTML, no React, no bundle): the consumer's server
// entry (apps/consumer/src/server.ts) for an error that escapes TanStack Start, its Node server (node-server.mjs) and
// the three apps' dev proxies (vite.config.ts) when the BFF behind them is down. Before this, such an error reached the
// browser as h3's default `{"status":500,"unhandled":true,"message":"HTTPError"}` (or Vite's empty 500).
//
// The brand's look from the theme (generated/theme.json: colours, fonts, the mark), bilingual (`?lang=`, the
// `nl.locale` cookie, Accept-Language), the status kept (404, 500, 502, 503, 504…), "Try again" (a link to the same
// address: no script needed), "Go to home", the support address with a short reference that is also in the server's
// log line. 502/503/504 try again on their own with a backoff (2, 4, 8, 16, 30 s; five times). In development the page
// also shows the error's name, message and stack and what usually causes it; production never shows a stack.
// Plain ES module so Node runs it without a build (node-server.mjs, the Vite configs) and Vite bundles it (server.ts).
import theme from '@northline/tokens/theme.json' with { type: 'json' };

const LOCALE_COOKIE = 'nl.locale';

/** The page's copy (en / fr-CA). `{brand}` is the product's name, `{seconds}` the wait before the next try. */
export const ERROR_MESSAGES = {
  en: {
    notFoundTitle: 'We couldn’t find that page.',
    notFoundBody: 'The link may be old, or the page has moved.',
    unreachableSiteTitle: 'We can’t reach the shop right now.',
    unreachableAppTitle: 'We can’t reach {brand} right now.',
    unreachableBody: 'Our servers aren’t answering at the moment. It’s not you — give us a minute and try again.',
    errorTitle: 'Something went wrong on our side.',
    errorBody: 'It’s not you. We’ve logged the problem; try again in a moment.',
    tryAgain: 'Try again',
    goHome: 'Go to home',
    retrying: 'Trying again in {seconds} seconds…',
    gaveUp: 'Still not answering. Try again in a few minutes.',
    support: 'Contact support',
    supportBody: 'Still stuck? Quote this reference when you write to us, so we can find what happened.',
    reference: 'Reference',
    status: 'Error {status}',
    pageTitle: 'Error {status} · {brand}',
    devTitle: 'Developer details (development only)',
    devHints: 'Likely causes',
  },
  fr: {
    notFoundTitle: 'Page introuvable.',
    notFoundBody: 'Le lien est peut-être ancien, ou la page a été déplacée.',
    unreachableSiteTitle: 'Impossible de joindre la boutique pour le moment.',
    unreachableAppTitle: 'Impossible de joindre {brand} pour le moment.',
    unreachableBody: 'Nos serveurs ne répondent pas en ce moment. Le problème ne vient pas de vous — patientez une minute et réessayez.',
    errorTitle: 'Un problème est survenu de notre côté.',
    errorBody: 'Le problème ne vient pas de vous. Nous l’avons consigné; réessayez dans un instant.',
    tryAgain: 'Réessayer',
    goHome: 'Aller à l’accueil',
    retrying: 'Nouvel essai dans {seconds} secondes…',
    gaveUp: 'Toujours pas de réponse. Réessayez dans quelques minutes.',
    support: 'Contacter le soutien',
    supportBody: 'Toujours bloqué? Indiquez cette référence en nous écrivant, pour que nous puissions retrouver ce qui s’est passé.',
    reference: 'Référence',
    status: 'Erreur {status}',
    pageTitle: 'Erreur {status} · {brand}',
    devTitle: 'Détails pour les développeurs (développement seulement)',
    devHints: 'Causes probables',
  },
};

/** 404 → not found; 502, 503, 504 → the servers behind can't be reached; anything else → an error on our side. */
export const errorKind = status => (status === 404 ? 'notFound' : status === 502 || status === 503 || status === 504 ? 'unreachable' : 'error');

/** A short reference for one failure (shown on the page, in the log line): 8 characters, no look-alikes. */
export function errorReference() {
  const alphabet = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  const chars = Array.from(bytes, b => alphabet[b % alphabet.length]).join('');
  return `${chars.slice(0, 4)}-${chars.slice(4)}`;
}

/** Whether the browser puts French before English in Accept-Language (q-weights; `*` or none = English). */
function prefersFrench(acceptLanguage) {
  const ranges = String(acceptLanguage ?? '').split(',').map((part, i) => {
    const [tag = '', ...params] = part.trim().toLowerCase().split(';');
    const q = params.map(p => /^\s*q=([\d.]+)\s*$/.exec(p)?.[1]).find(Boolean);
    return { lang: tag.trim().split('-')[0] ?? '', q: q === undefined ? 1 : Number(q), i };
  }).filter(r => r.lang && r.q > 0 && !Number.isNaN(r.q));
  ranges.sort((a, b) => b.q - a.q || a.i - b.i);
  return ranges.find(r => r.lang === 'fr' || r.lang === 'en' || r.lang === '*')?.lang === 'fr';
}

/** One cookie's value from a Cookie header; a malformed value is ignored, never thrown. */
function cookieValue(header, name) {
  for (const part of String(header ?? '').split(';')) {
    const at = part.indexOf('=');
    if (at < 0 || part.slice(0, at).trim() !== name) continue;
    try { return decodeURIComponent(part.slice(at + 1).trim()); } catch { return undefined; }
  }
  return undefined;
}

/** The page's language as the app picks it: `?lang=`, then the `nl.locale` cookie, then Accept-Language. */
export function errorLocale({ url, cookie, acceptLanguage } = {}) {
  let lang = null;
  try { lang = new URL(url ?? '/', 'http://x').searchParams.get('lang'); } catch { /* a malformed address: no ?lang */ }
  if (lang === 'fr' || lang === 'en') return lang;
  const saved = cookieValue(cookie, LOCALE_COOKIE);
  if (saved === 'fr' || saved === 'en') return saved;
  return prefersFrench(acceptLanguage) ? 'fr' : 'en';
}

/**
 * Whether a request should get JSON rather than the page: the BFF's and the api's paths and the server functions
 * (`/api/`, `/bff/`, `/oauth2/` token calls are never pages), or an Accept that asks for JSON and not HTML.
 */
export function wantsJson({ path, accept }) {
  if (/^\/(api|bff|_serverFn)(\/|$)/.test(path ?? '')) return true;
  const a = String(accept ?? '').toLowerCase();
  return a.includes('application/json') && !a.includes('text/html');
}

/** The JSON answer for an API caller: the status, a stable code and the reference — never a stack. */
export function errorJson(status, ref) {
  const code = status === 404 ? 'not_found' : status === 502 ? 'bad_gateway' : status === 503 ? 'service_unavailable' : status === 504 ? 'gateway_timeout' : 'internal_error';
  return JSON.stringify({ status, error: code, ref });
}

/**
 * What usually causes an error in local development (shown in development only): the consumer-bff or the api not
 * running, a dependency added since the last `pnpm install` (Vite keeps a failed import until it restarts).
 */
export function devHints(error) {
  const text = describe(error);
  const hints = [];
  if (/ECONNREFUSED|fetch failed|socket hang up|ECONNRESET|connect ETIMEDOUT|TimeoutError|aborted due to timeout|is not answering/i.test(text)) {
    const port = /:(\d{4,5})\b/.exec(text)?.[1] ?? '8081';
    const local = LOCAL_SERVICES[port];
    hints.push(local
      ? `The ${local.name} on :${port} is not answering. Start it: make up SERVICES="${local.services}" (or make up-all).${local.devAuth ? ` Without a BFF: DEV_AUTH=1 make up SERVICES="${local.devAuth}".` : ''}`
      : `Nothing answers on :${port}. make status shows what runs; make up-all starts everything.`);
  }
  if (/\b50[234]\b|Bad Gateway|Service Unavailable|Gateway Timeout/i.test(text)) {
    hints.push('The BFF answered but the api behind it did not: make up SERVICES="api", then make logs SERVICES=api.');
  }
  const pkg = /Cannot find (?:module|package) '(@northline\/[^/']+)/.exec(text)?.[1];
  if (pkg) {
    hints.push(`A workspace package is missing (${pkg}) — run pnpm install in web/ (or make dev again), then restart the dev server (make down SERVICES=consumer, then make up SERVICES=consumer). A dev server that was running across a git pull keeps the failed import until it restarts.`);
  } else if (/ERR_MODULE_NOT_FOUND|Cannot find module|Cannot find package|Failed to resolve import|does not provide an export named|Failed to load url/i.test(text)) {
    hints.push('A dependency or file is missing — usually a stale install after a pull: run pnpm install in web/ (or make dev again), then restart the dev server (make down SERVICES=consumer, then make up SERVICES=consumer). Vite remembers a failed import until it restarts.');
  }
  if (/ZodError|Invalid input|Unexpected token .* JSON|is not valid JSON/i.test(text)) {
    hints.push('A service answered something this app cannot read (an HTML error page, an older api): check that the api and the BFF are on the same commit as the web app.');
  }
  if (!hints.length) hints.push('The dev server’s terminal (or make logs SERVICES=consumer) has the full error with this reference.');
  return hints;
}

/** The local ports (scripts/stack.sh) and what to start for each. */
const LOCAL_SERVICES = {
  8080: { name: 'api', services: 'auth api bff-consumer consumer' },
  8081: { name: 'consumer-bff', services: 'auth api bff-consumer consumer', devAuth: 'api consumer' },
  8082: { name: 'studio-bff', services: 'auth api bff studio', devAuth: 'api studio' },
  8083: { name: 'console-bff', services: 'auth api bff-console console', devAuth: 'api console' },
  9000: { name: 'auth server', services: 'auth api bff-consumer consumer' },
};

/** An error as text, with its causes (fetch's "fetch failed" hides ECONNREFUSED in `cause`). */
function describe(error, depth = 0) {
  if (!error || depth > 4) return '';
  if (typeof error !== 'object') return String(error);
  const own = `${error.name ?? ''}: ${error.message ?? ''} ${error.code ?? ''} ${error.status ?? ''}`;
  return `${own} ${describe(error.cause, depth + 1)}`;
}

/** The error's own name, message and stack (development only), its causes' messages appended. */
export function errorDetail(error) {
  const e = error && typeof error === 'object' ? error : { message: String(error) };
  const causes = [];
  for (let c = e.cause, i = 0; c && i < 4; c = c.cause, i++) causes.push(`${c.name ?? 'Error'}: ${c.message ?? String(c)}${c.code ? ` (${c.code})` : ''}`);
  return {
    name: String(e.name ?? 'Error'),
    message: [String(e.message ?? ''), ...causes.map(c => `caused by ${c}`)].filter(Boolean).join('\n'),
    stack: typeof e.stack === 'string' ? e.stack : '',
    hints: devHints(error),
  };
}

/**
 * One structured log line for a failure (the log pipeline reads JSON lines): the reference, status, method and path —
 * without the query string, which can carry an e-mail address or a search — and the error with its stack. No headers,
 * cookies or bodies (no personal information).
 */
export function logError({ ref, status, method, url, error, source = 'web' }, log = console.error) {
  let path = '/';
  try { path = new URL(url ?? '/', 'http://x').pathname; } catch { /* keep "/" */ }
  const detail = errorDetail(error);
  log(JSON.stringify({ level: 'error', event: 'web.request_failed', source, ref, status, method, path, error: { name: detail.name, message: detail.message, stack: detail.stack } }));
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ESCAPES[c]);
const fill = (text, values) => text.replace(/\{(\w+)\}/g, (_, k) => (values[k] === undefined ? `{${k}}` : String(values[k])));

/** The theme's colours this page uses, as the same custom properties the apps use (tokens.css). */
const COLOURS = ['canvas', 'surface', 'surface-sunken', 'footer-bg', 'footer-text', 'footer-muted', 'mark', 'mark-text', 'mark-highlight', 'selected', 'selected-text', 'text', 'text-muted', 'border', 'border-card', 'action-primary', 'action-primary-hover', 'action-primary-text', 'action-accent', 'action-neutral', 'action-neutral-hover', 'action-neutral-text', 'action-neutral-border', 'link', 'focus', 'focus-ring', 'danger', 'danger-bg', 'surface-brand'];

function themeCss() {
  const colours = COLOURS.filter(c => theme.color[c]).map(c => `--color-${c}:${theme.color[c]}`).join(';');
  return `:root{${colours};--font-sans:${theme.font.sans};--font-display:${theme.font.display}}`;
}

/** The brand's mark (generated/theme.json `logo.mark`) as inline SVG, its fills as the theme's colours. */
function markSvg() {
  const mark = theme.logo?.mark;
  if (!mark) return '';
  const shapes = mark.shapes.map(({ el, ...attrs }) => `<${el} ${Object.entries(attrs).map(([k, v]) => `${k}="${escape(k === 'fill' || k === 'stroke' ? `var(--color-${v})` : v)}"`).join(' ')}/>`).join('');
  return `<svg class="mark" viewBox="${escape(mark.viewBox)}" width="32" height="32" aria-hidden="true" focusable="false">${shapes}</svg>`;
}

const STYLE = `
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:var(--color-canvas);color:var(--color-text);font:16px/1.5 var(--font-sans)}
a{color:var(--color-link)}
a:focus-visible{outline:3px solid var(--color-focus);outline-offset:2px}
.top{display:flex;align-items:center;min-height:64px;padding:0 24px;border-bottom:1px solid var(--color-border);background:var(--color-surface)}
.brand{display:inline-flex;align-items:center;gap:10px;min-height:44px;color:var(--color-text);text-decoration:none;font:700 20px/1 var(--font-sans);letter-spacing:-.01em}
main{flex:1;display:flex;justify-content:center;padding:56px 24px 64px}
.card{width:100%;max-width:600px;text-align:center}
.art{display:flex;justify-content:center;margin:0 auto 24px}
.art span{display:grid;place-items:center;width:112px;height:112px;border-radius:50%;background:var(--color-surface-brand);color:var(--color-action-primary)}
.chip{display:inline-block;margin:0 0 12px;padding:4px 12px;border-radius:999px;border:1px solid var(--color-border-card);color:var(--color-text-muted);font-size:14px;font-weight:600}
h1{margin:0;font:700 clamp(28px,5vw,40px)/1.15 var(--font-sans);letter-spacing:-.02em}
.body{margin:12px auto 0;max-width:34em;color:var(--color-text-muted);font-size:18px}
.retry{min-height:1.5em;margin:12px 0 0;color:var(--color-text-muted);font-size:15px}
.actions{display:flex;flex-wrap:wrap;justify-content:center;gap:12px;margin-top:28px}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:48px;padding:0 24px;border-radius:999px;font-weight:600;font-size:16px;text-decoration:none;border:1px solid transparent}
.btn-primary{background:var(--color-action-primary);color:var(--color-action-primary-text)}
.btn-primary:hover{background:var(--color-action-primary-hover)}
.btn-secondary{background:var(--color-action-neutral);color:var(--color-action-neutral-text);border-color:var(--color-action-neutral-border)}
.btn-secondary:hover{background:var(--color-action-neutral-hover)}
.btn:focus-visible{outline:3px solid var(--color-focus);outline-offset:2px}
.help{margin:32px auto 0;padding:16px 20px;max-width:34em;border-radius:16px;background:var(--color-surface-sunken);border:1px solid var(--color-border);color:var(--color-text-muted);font-size:15px}
.help p{margin:0}
.help a{display:inline-flex;align-items:center;justify-content:center;min-height:44px;margin-top:4px;font-weight:600}
.ref{margin-top:6px!important;color:var(--color-text);font:600 15px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.04em}
.dev{width:calc(100% - 32px);max-width:900px;min-width:0;margin:0 auto 48px;text-align:left;padding:20px;border-radius:16px;border:1px solid var(--color-danger);background:var(--color-danger-bg)}
.dev h2{margin:0 0 8px;font-size:18px}
.dev h3{margin:16px 0 4px;font-size:15px}
.dev p{margin:0;white-space:pre-wrap;word-break:break-word}
.dev ul{margin:0;padding-left:20px}
.dev li{margin:4px 0}
.dev pre{margin:12px 0 0;padding:12px;max-width:100%;max-height:420px;overflow:auto;border-radius:8px;background:var(--color-surface);border:1px solid var(--color-border);font-size:12px;line-height:1.5;white-space:pre}
footer{padding:24px;background:var(--color-footer-bg);color:var(--color-footer-muted);font-size:14px;text-align:center}
@media (max-width:599.98px){main{padding:32px 16px 48px}.top{padding:0 16px}.actions{flex-direction:column;align-items:stretch}.body{font-size:17px}}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
`;

// Phosphor's CloudSlash (unreachable), Compass (not found) and Warning (error), regular weight: the apps' icon set.
const ICONS = {
  unreachable: 'M53.92,34.62A8,8,0,1,0,42.08,45.38L81.32,88.55l-.06.12A65,65,0,0,0,72,88a64,64,0,0,0,0,128h88a87.34,87.34,0,0,0,31.8-5.93l10.28,11.31a8,8,0,1,0,11.84-10.76ZM160,200H72a48,48,0,0,1,0-96c1.1,0,2.2,0,3.3.12A88.4,88.4,0,0,0,72,128a8,8,0,0,0,16,0,72.25,72.25,0,0,1,5.06-26.54l87,95.7A71.66,71.66,0,0,1,160,200Zm88-72a87.89,87.89,0,0,1-22.35,58.61A8,8,0,0,1,213.71,176,72,72,0,0,0,117.37,70a8,8,0,0,1-9.48-12.89A88,88,0,0,1,248,128Z',
  notFound: 'M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216ZM172.42,72.84l-64,32a8.05,8.05,0,0,0-3.58,3.58l-32,64A8,8,0,0,0,80,184a8.1,8.1,0,0,0,3.58-.84l64-32a8.05,8.05,0,0,0,3.58-3.58l32-64a8,8,0,0,0-10.74-10.74ZM138,138,97.89,158.11,118,118l40.15-20.07Z',
  error: 'M236.8,188.09,149.35,36.22h0a24.76,24.76,0,0,0-42.7,0L19.2,188.09a23.51,23.51,0,0,0,0,23.72A24.35,24.35,0,0,0,40.55,224h174.9a24.35,24.35,0,0,0,21.33-12.19A23.51,23.51,0,0,0,236.8,188.09ZM222.93,203.8a8.5,8.5,0,0,1-7.48,4.2H40.55a8.5,8.5,0,0,1-7.48-4.2,7.59,7.59,0,0,1,0-7.72L120.52,44.21a8.75,8.75,0,0,1,15,0l87.45,151.87A7.59,7.59,0,0,1,222.93,203.8ZM120,144V104a8,8,0,0,1,16,0v40a8,8,0,0,1-16,0Zm20,36a12,12,0,1,1-12-12A12,12,0,0,1,140,180Z',
};

// 502/503/504: reload with a backoff (2, 4, 8, 16, 30 s), five times per address within ten minutes, then stop.
const RETRY_SCRIPT = `(function(){var k='nl.retry:'+location.pathname+location.search,s={n:0,t:0};try{s=JSON.parse(sessionStorage.getItem(k)||'{}')||s}catch(e){}if(!s.t||Date.now()-s.t>6e5)s={n:0,t:Date.now()};var el=document.getElementById('nl-retry');if(!el)return;var d=[2,4,8,16,30][s.n];if(d===undefined){el.textContent=el.getAttribute('data-gave-up');return}el.textContent=el.getAttribute('data-wait').replace('{seconds}',d);if(el.getAttribute('data-reload')==='0')return;setTimeout(function(){s.n++;try{sessionStorage.setItem(k,JSON.stringify(s))}catch(e){}location.reload()},d*1000)})();`;

/**
 * The error page's HTML.
 *  status   the HTTP status (kept on the response)
 *  locale   'en' | 'fr' (errorLocale())
 *  ref      the reference (errorReference()), also in the log line
 *  url      this request's address: "Try again" goes back to it (same origin only)
 *  audience 'site' (the consumer: "the shop") or 'app' (the Studio, the console: the product's name)
 *  detail   development only: errorDetail(error) — name, message, stack, hints
 *  nonce    the page's CSP nonce (the retry script); without one in production the page simply doesn't retry
 *  supportEmail  the support mailbox (NL_SUPPORT_EMAIL), else the brand's
 *  reload   false: show the countdown but never reload (the preview page)
 */
export function renderErrorPage({ status = 500, locale = 'en', ref, url = '/', audience = 'site', detail, nonce, supportEmail, homeHref = '/', reload = true } = {}) {
  const m = ERROR_MESSAGES[locale === 'fr' ? 'fr' : 'en'];
  const kind = errorKind(status);
  const brandName = theme.brand.name;
  const values = { brand: brandName, status };
  const title = kind === 'notFound' ? m.notFoundTitle : kind === 'unreachable' ? (audience === 'app' ? m.unreachableAppTitle : m.unreachableSiteTitle) : m.errorTitle;
  const body = kind === 'notFound' ? m.notFoundBody : kind === 'unreachable' ? m.unreachableBody : m.errorBody;
  let again = '/';
  try { const u = new URL(url, 'http://x'); again = u.pathname + u.search; } catch { /* home */ }
  const mail = supportEmail || theme.brand.supportEmail;
  const subject = encodeURIComponent(`${fill(m.status, values)} · ${m.reference} ${ref ?? ''}`.trim());
  const lang = locale === 'fr' ? 'fr-CA' : 'en-CA';
  const actions = [
    kind !== 'notFound' ? `<a class="btn btn-primary" href="${escape(again)}">${escape(m.tryAgain)}</a>` : '',
    `<a class="btn ${kind === 'notFound' ? 'btn-primary' : 'btn-secondary'}" href="${escape(homeHref)}">${escape(m.goHome)}</a>`,
  ].join('');
  const dev = detail ? `
<section class="dev" aria-labelledby="nl-dev-title">
  <h2 id="nl-dev-title">${escape(m.devTitle)}</h2>
  <p><strong>${escape(detail.name)}</strong>: ${escape(detail.message)}</p>
  ${detail.hints?.length ? `<h3>${escape(m.devHints)}</h3><ul>${detail.hints.map(h => `<li>${escape(h)}</li>`).join('')}</ul>` : ''}
  ${detail.stack ? `<pre tabindex="0" aria-label="Stack">${escape(detail.stack)}</pre>` : ''}
</section>` : '';
  const retry = kind === 'unreachable'
    ? `<p class="retry" id="nl-retry" role="status" data-wait="${escape(m.retrying)}" data-gave-up="${escape(m.gaveUp)}"${reload ? '' : ' data-reload="0"'}></p><script${nonce ? ` nonce="${escape(nonce)}"` : ''}>${RETRY_SCRIPT}</script>`
    : '';
  return `<!DOCTYPE html>
<html lang="${lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escape(fill(m.pageTitle, values))}</title>
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<style>${themeCss()}${STYLE}</style>
</head>
<body>
<header class="top"><a class="brand" href="${escape(homeHref)}">${markSvg()}<span>${escape(brandName)}</span></a></header>
<main id="main">
  <div class="card">
    <div class="art" aria-hidden="true"><span><svg width="56" height="56" viewBox="0 0 256 256" fill="currentColor" focusable="false"><path d="${ICONS[kind]}"/></svg></span></div>
    <p class="chip">${escape(fill(m.status, values))}</p>
    <h1>${escape(fill(title, values))}</h1>
    <p class="body">${escape(body)}</p>
    ${retry}
    <div class="actions">${actions}</div>
    <div class="help"><p>${escape(m.supportBody)}</p>${ref ? `<p class="ref">${escape(m.reference)} ${escape(ref)}</p>` : ''}<a href="mailto:${escape(mail)}?subject=${subject}">${escape(m.support)}</a></div>
  </div>
</main>
${dev}
<footer>${escape(theme.brand.legalName)}</footer>
</body>
</html>`;
}

/**
 * The whole answer for a failed request: the page (or JSON for an API caller) with `status`, no caching, the
 * reference in `x-nl-error-ref`; and the log line. `request` is a Request or { url, method, headers }.
 */
export function errorResponseParts({ status, error, request, dev = false, audience = 'site', nonce, supportEmail, ref = errorReference(), log = console.error, source, reload = true }) {
  const headers = request?.headers;
  const header = name => (typeof headers?.get === 'function' ? headers.get(name) : headers?.[name]) ?? undefined;
  const url = request?.url ?? '/';
  let path = '/';
  try { path = new URL(url, 'http://x').pathname; } catch { /* "/" */ }
  if (error !== undefined || status >= 500) logError({ ref, status, method: request?.method ?? 'GET', url, error: error ?? new Error(`HTTP ${status}`), source }, log);
  const common = { 'cache-control': 'no-store', 'x-nl-error-ref': ref };
  if (wantsJson({ path, accept: header('accept') })) {
    return { status, headers: { ...common, 'content-type': 'application/json; charset=utf-8' }, body: errorJson(status, ref), ref };
  }
  const locale = errorLocale({ url, cookie: header('cookie'), acceptLanguage: header('accept-language') });
  const body = renderErrorPage({ status, locale, ref, url, audience, nonce, supportEmail, reload, detail: dev && error !== undefined ? errorDetail(error) : undefined });
  return { status, headers: { ...common, 'content-type': 'text/html; charset=utf-8', 'content-language': locale === 'fr' ? 'fr-CA' : 'en-CA' }, body, ref };
}

/**
 * Vite's dev proxy (`server.proxy[...].configure`): when the BFF behind /api, /bff, /oauth2 or /login is not running,
 * answer 502 with this page for a page load (Sign in → /oauth2/authorization/…) and JSON for the app's own calls,
 * instead of Vite's empty 500. The hint names the service to start.
 */
export function proxyErrors({ audience = 'site', service = 'the BFF' } = {}) {
  return (proxy, options) => {
    proxy.on('error', (error, req, res) => {
      if (!res || typeof res.writeHead !== 'function' || res.headersSent || res.writableEnded) return;
      const cause = Object.assign(new Error(`${service} at ${options?.target ?? 'its port'} is not answering (${error?.code ?? error?.message ?? 'error'})`), { cause: error });
      const answer = errorResponseParts({ status: 502, error: cause, request: { url: req.url, method: req.method, headers: req.headers }, dev: true, audience, source: 'dev-proxy' });
      res.writeHead(answer.status, answer.headers);
      res.end(answer.body);
    });
  };
}
