import { useRouteContext } from '@tanstack/react-router';
import { brand } from '@northline/tokens';
import type { PageHost } from './pages';

/**
 * Public runtime configuration (one image serves every environment; the chart sets NL_AUTH_ORIGIN and NL_SITE_ORIGIN).
 * `legalEntity` is the footer's company line (NL_LEGAL_ENTITY): legal entity data, configuration rather than copy
 * (S-134 — no city in code or messages).
 * `siteOrigin` is the consumer site (the apex) — links leave a merchant's own domain for it. `studioOrigin` is the Studio
 * (NL_STUDIO_ORIGIN, the chart's urls.studio): "Sell or offer a service" enters its onboarding (S-61). `page` is set when this
 * request is a business page on `pages.<zone>` or a merchant's own domain (server/page-hosts.mjs, S-54). Read on the
 * server by `publicConfig()` (request.ts), serialized into the page, read back in the browser. `supportEmail` is the
 * support mailbox the Help page names (NL_SUPPORT_EMAIL, S-145); unset, Help sends people to Help & cases only.
 */
export interface PublicConfig { authOrigin: string; siteOrigin: string; legalEntity: string; studioOrigin: string; supportEmail?: string | null; page?: PageHost | null }
declare global { interface Window { __NL_CONFIG__?: PublicConfig } }

export const FALLBACK_CONFIG: PublicConfig = { authOrigin: 'http://localhost:9000', siteOrigin: 'http://localhost:3000', legalEntity: brand.legalName, studioOrigin: 'http://localhost:3100', supportEmail: null, page: null };

const PRIVATE_V4 = /^(10\.\d{1,3}|172\.(1[6-9]|2\d|3[01])|192\.168|169\.254)\.\d{1,3}\.\d{1,3}$/;
const LOOPBACK = /^(localhost|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|\[::1\])$/;

/**
 * The local dev server only (NL_DEV_HOST, `make mobile-dev`; docs/runbooks/mobile-local.md): a page requested on a
 * private-network host — a phone on the Wi-Fi asking for `http://192.168.1.20:3000/sign-in`, an Android emulator for
 * `http://10.0.2.2:3000/…` — gets that host in place of a configured loopback one (`http://localhost:9000` →
 * `http://192.168.1.20:9000`), so the phone's browser reaches this machine's auth server. Anything else is unchanged.
 */
export function onRequestHost(origin: string, requestHost: string | null | undefined): string {
  const host = (requestHost ?? '').replace(/:\d+$/, '').toLowerCase();
  if (!PRIVATE_V4.test(host) && !(host.endsWith('.local') && host.length > 6)) return origin;
  try {
    const url = new URL(origin);
    if (!LOOPBACK.test(url.hostname)) return origin;
    url.hostname = host;
    return url.origin;
  } catch {
    return origin;
  }
}

/** The configuration from the root route's context (a render without it — tests — gets the local defaults). */
export function useSiteConfig(): PublicConfig {
  const context = useRouteContext({ strict: false }) as { config?: PublicConfig };
  return context.config ?? FALLBACK_CONFIG;
}
