import { describe, expect, it } from 'vitest';
import { contentSecurityPolicy, securityHeaders } from '../../server/security-headers.mjs';

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
    expect(d['script-src']).toEqual(["'self'", "'unsafe-inline'", 'https://js.stripe.com']);
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
