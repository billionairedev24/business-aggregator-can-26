// Response headers the consumer server sets on every answer (S-104). The Studio and console get theirs from nginx
// (web/docker/security-headers.inc.template); this is the same policy for the SSR app, plus a Content-Security-Policy.
//
// script-src keeps 'unsafe-inline': the SSR document carries inline scripts (the public configuration, TanStack
// Start's hydration state, JSON-LD) without nonces yet. The policy still pins where scripts, frames, forms and
// connections may come from or go to, forbids plugins and <base> rewrites, and keeps the site out of frames.

/** The auth origin the browser calls (JSON sign-in, form posts), from NL_AUTH_ORIGIN like the app's config. */
function origin(value, fallback) {
  try {
    return new URL(value ?? fallback).origin;
  } catch {
    return new URL(fallback).origin;
  }
}

/** The Content-Security-Policy of the consumer site for this auth origin. */
export function contentSecurityPolicy(env = process.env) {
  const auth = origin(env.NL_AUTH_ORIGIN, 'http://localhost:9000');
  return [
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline' https://js.stripe.com",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob: https:",
    `connect-src 'self' ${auth} https://api.stripe.com`,
    'frame-src https://js.stripe.com https://hooks.stripe.com',
    `form-action 'self' ${auth}`,
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
  ].join('; ');
}

/** Every answer's security headers (lower-case names, as Node writes them). */
export function securityHeaders(env = process.env) {
  return {
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'strict-origin-when-cross-origin',
    'x-frame-options': 'DENY',
    'permissions-policy': 'camera=(), microphone=(), payment=(self), geolocation=(self)',
    'cross-origin-opener-policy': 'same-origin-allow-popups',
    'content-security-policy': contentSecurityPolicy(env),
  };
}
