import { useRouteContext } from '@tanstack/react-router';
import type { PageHost } from './pages';

/**
 * Public runtime configuration (one image serves every environment; the chart sets NL_AUTH_ORIGIN and NL_SITE_ORIGIN).
 * `legalEntity` is the footer's company line (NL_LEGAL_ENTITY): legal entity data, configuration rather than copy
 * (S-134 — no city in code or messages).
 * `siteOrigin` is the consumer site (the apex) — links leave a merchant's own domain for it. `page` is set when this
 * request is a business page on `pages.<zone>` or a merchant's own domain (server/page-hosts.mjs, S-54). Read on the
 * server by `publicConfig()` (request.ts), serialized into the page, read back in the browser.
 */
export interface PublicConfig { authOrigin: string; siteOrigin: string; legalEntity: string; page?: PageHost | null }
declare global { interface Window { __NL_CONFIG__?: PublicConfig } }

export const FALLBACK_CONFIG: PublicConfig = { authOrigin: 'http://localhost:9000', siteOrigin: 'http://localhost:3000', legalEntity: 'Northline Marketplace Inc.', page: null };

/** The configuration from the root route's context (a render without it — tests — gets the local defaults). */
export function useSiteConfig(): PublicConfig {
  const context = useRouteContext({ strict: false }) as { config?: PublicConfig };
  return context.config ?? FALLBACK_CONFIG;
}
