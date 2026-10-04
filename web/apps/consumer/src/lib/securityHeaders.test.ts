import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { NONCE_HEADER, SCRIPT_INVENTORY, contentSecurityPolicy, createCspReporter, createNonce, cspReportLines, isCspReportPath, securityHeaders } from '../../server/security-headers.mjs';

const SRC = resolve(__dirname, '..');
const WEB = resolve(__dirname, '../../../..');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\./.test(name) ? [path] : [];
  });
}

const directives = (csp: string) => Object.fromEntries(csp.split('; ').map((d) => [d.split(' ')[0], d.split(' ').slice(1)]));

describe('consumer security headers (S-104)', () => {
  it('sends a Content-Security-Policy with every answer, next to the framing and sniffing headers', () => {
    const headers = securityHeaders({ NL_AUTH_ORIGIN: 'https://auth.staging.northline.ca' });
    expect(headers['x-frame-options']).toBe('DENY');
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['content-security-policy']).toBe(contentSecurityPolicy({ NL_AUTH_ORIGIN: 'https://auth.staging.northline.ca' }));
  });

  it('keeps the site out of frames, forbids plugins and <base>, and pins scripts to the site and Stripe', () => {
    const d = directives(contentSecurityPolicy({ NL_AUTH_ORIGIN: 'https://auth.staging.northline.ca' }));
    expect(d['frame-ancestors']).toEqual(["'none'"]);
    expect(d['object-src']).toEqual(["'none'"]);
    expect(d['base-uri']).toEqual(["'self'"]);
    expect(d['script-src']).toEqual(["'self'", 'https://js.stripe.com']);
  });

  it('lets the browser reach and post to the auth origin only (its origin, never a path)', () => {
    const d = directives(contentSecurityPolicy({ NL_AUTH_ORIGIN: 'https://auth.staging.northline.ca/some/path' }));
    expect(d['connect-src']).toContain('https://auth.staging.northline.ca');
    expect(d['form-action']).toEqual(["'self'", 'https://auth.staging.northline.ca']);
  });

  it('falls back to the local auth server when NL_AUTH_ORIGIN is missing or not a URL', () => {
    expect(directives(contentSecurityPolicy({}))['form-action']).toEqual(["'self'", 'http://localhost:9000']);
    expect(directives(contentSecurityPolicy({ NL_AUTH_ORIGIN: 'not a url' }))['form-action']).toEqual(["'self'", 'http://localhost:9000']);
  });
});

describe('payment-page scripts and violation reports (S-110)', () => {
  const policy = contentSecurityPolicy({ NL_AUTH_ORIGIN: 'https://auth.northline.test/' });
  const directive = (name: string) => policy.split('; ').find(d => d.startsWith(`${name} `))?.split(' ').slice(1) ?? [];

  it('lets scripts in only from this site and the inventory', () => {
    expect(directive('script-src')).toEqual(["'self'", 'https://js.stripe.com']);
    expect(directive('frame-src').every(s => s.endsWith('stripe.com'))).toBe(true);
    expect(directive('object-src')).toEqual(["'none'"]);
    expect(directive('base-uri')).toEqual(["'self'"]);
    expect(directive('connect-src')).toContain('https://auth.northline.test');
    expect(directive('form-action')).toEqual(["'self'", 'https://auth.northline.test']);
    expect(directive('report-uri')).toEqual(['/csp-report']);
  });

  it('is one enforced policy that reports to /csp-report', () => {
    const headers = securityHeaders({ NL_AUTH_ORIGIN: 'https://auth.northline.test' });
    expect(headers['content-security-policy']).toContain("default-src 'self'");
    expect(headers['content-security-policy-report-only']).toBeUndefined();
    expect(directive('report-to')).toEqual(['csp']);
    expect(headers['reporting-endpoints']).toBe('csp="/csp-report"');
    expect(isCspReportPath('/csp-report')).toBe(true);
    expect(isCspReportPath('/csp-report/x')).toBe(false);
  });

  it('the inventory names every third-party script the code loads', () => {
    const inventory = new Set(SCRIPT_INVENTORY.map(s => s.origin));
    const loaded = new Set<string>();
    for (const file of sources(SRC)) {
      for (const m of readFileSync(file, 'utf8').matchAll(/\.src\s*=\s*['"`](https?:\/\/[^'"`/]+)/g)) loaded.add(m[1]!);
    }
    expect(loaded.size).toBeGreaterThan(0);
    for (const origin of loaded) expect(inventory, `${origin} is loaded but not in SCRIPT_INVENTORY`).toContain(origin);
    for (const s of SCRIPT_INVENTORY) expect(s.why.length).toBeGreaterThan(10);
  });
});

describe('script nonces (S104-09, SAQ A E7)', () => {
  it('a page’s policy allows its nonce and no inline script otherwise — no unsafe-inline, no unsafe-eval', () => {
    const nonce = createNonce();
    const d = directives(contentSecurityPolicy({ NL_AUTH_ORIGIN: 'https://auth.northline.test' }, nonce));
    expect(d['script-src']).toEqual(["'self'", `'nonce-${nonce}'`, 'https://js.stripe.com']);
    expect(securityHeaders({}, nonce)['content-security-policy']).toContain(`'nonce-${nonce}'`);
    for (const policy of [contentSecurityPolicy({}), contentSecurityPolicy({}, nonce)]) {
      const script = directives(policy)['script-src'];
      expect(script).not.toContain("'unsafe-inline'");
      expect(script).not.toContain("'unsafe-eval'");
      expect(script).not.toContain("'strict-dynamic'");
    }
    expect(securityHeaders({})['content-security-policy']).not.toContain('nonce-');
  });

  it('every nonce is fresh: 128 random bits in base64', () => {
    const nonces = Array.from({ length: 200 }, createNonce);
    expect(new Set(nonces).size).toBe(200);
    for (const n of nonces) {
      expect(n).toMatch(/^[A-Za-z0-9+/]{22}==$/);
      expect(Buffer.from(n, 'base64')).toHaveLength(16);
    }
    expect(NONCE_HEADER).toBe('x-nl-csp-nonce');
  });

  it('the app’s own inline scripts carry the nonce, and the router gets it', () => {
    const inline: string[] = [];
    for (const file of sources(SRC)) {
      const code = readFileSync(file, 'utf8').split('\n').filter(l => !/^\s*(\*|\/\/|\/\*)/.test(l)).join('\n'); // not comments
      for (const m of code.matchAll(/<script\b([^>]*)>/g)) if (!/\bsrc=/.test(m[1]!)) inline.push(`${file}: ${m[0]}`);
    }
    expect(inline.length).toBeGreaterThan(0);
    for (const tag of inline) expect(tag, 'an inline <script> without the nonce').toMatch(/\bnonce=\{nonce\}/);
    expect(readFileSync(join(SRC, 'router.tsx'), 'utf8')).toMatch(/ssr: \{ nonce: cspNonce\(\) \}/);
    const server = readFileSync(resolve(SRC, '../server/node-server.mjs'), 'utf8');
    expect(server).toMatch(/headers\.set\(NONCE_HEADER, nonce\)/); // a client's header is replaced, never trusted
  });

  it('the Studio and console (SPAs on nginx) need no inline script either', () => {
    const nginx = readFileSync(join(WEB, 'docker/security-headers.inc.template'), 'utf8');
    const policy = /Content-Security-Policy "([^"]+)"/.exec(nginx)![1]!;
    const script = directives(policy)['script-src']!;
    expect(script).not.toContain("'unsafe-inline'");
    expect(script).not.toContain("'unsafe-eval'");
    for (const app of ['studio', 'console']) {
      const html = readFileSync(join(WEB, `apps/${app}/index.html`), 'utf8');
      const tags = [...html.matchAll(/<script\b([^>]*)>/g)];
      expect(tags.length).toBeGreaterThan(0);
      for (const t of tags) expect(t[0], `${app}/index.html: an inline script`).toMatch(/\bsrc=/);
      expect(html).not.toMatch(/\son[a-z]+=/i); // no inline event handlers
    }
  });
});

describe('POST /csp-report', () => {
  const legacy = JSON.stringify({ 'csp-report': {
    'document-uri': 'https://northline.test/cart?token=secret#x', 'violated-directive': 'script-src-elem',
    'effective-directive': 'script-src-elem', 'blocked-uri': 'https://evil.example/skim.js?c=4242', disposition: 'report',
  } });

  it('logs one line per violation without queries', () => {
    const [line] = cspReportLines(legacy);
    const parsed = JSON.parse(line!);
    expect(parsed).toMatchObject({ 'event.dataset': 'csp.violation', 'csp.directive': 'script-src-elem',
      'csp.blocked': 'https://evil.example/skim.js', 'csp.document': 'https://northline.test/cart' });
    expect(line).not.toContain('secret');
    expect(line).not.toContain('4242');
  });

  it('reads the Reporting API format and ignores other reports', () => {
    const body = JSON.stringify([
      { type: 'csp-violation', body: { documentURL: 'https://northline.test/', effectiveDirective: 'frame-src', blockedURL: 'inline' } },
      { type: 'deprecation', body: {} },
    ]);
    expect(cspReportLines(body)).toHaveLength(1);
    expect(JSON.parse(cspReportLines(body)[0]!)['csp.blocked']).toBe('inline');
    expect(cspReportLines('not json')).toEqual([]);
    expect(cspReportLines('')).toEqual([]);
  });

  it('caps the lines a minute and refuses big bodies', () => {
    const lines: string[] = [];
    let t = 0;
    const report = createCspReporter({ log: l => lines.push(l), perMinute: 2, now: () => t });
    expect(report(legacy)).toBe(204);
    report(legacy);
    report(legacy);
    expect(lines).toHaveLength(2);
    t = 61_000;
    report(legacy);
    expect(lines).toHaveLength(3);
    expect(report('x'.repeat(20_000))).toBe(413);
  });
});
