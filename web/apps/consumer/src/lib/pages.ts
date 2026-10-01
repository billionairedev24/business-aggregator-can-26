import type { LocationRewrite } from '@tanstack/react-router';

/**
 * A business page served on another host (S-54): `pages.<zone>/<slug>` (`mode: pages`) or a merchant's own domain
 * (`mode: custom`, the page at `/`). server/page-hosts.mjs decides it per request; the router maps the host's path onto
 * `/providers/<slug>` and back, so server rendering and hydration agree on the route while the address bar keeps the
 * merchant's URL. Everything else about the site (booking, sign-in, the cart) lives on the site origin.
 */
export interface PageHost { mode: 'custom' | 'pages'; host: string; slug: string }

export const pageEntry = (page: PageHost) => (page.mode === 'custom' ? '/' : `/${page.slug}`);

export function pageRewrite(page: PageHost | null | undefined): LocationRewrite | undefined {
  if (!page) return undefined;
  const entry = pageEntry(page);
  const inside = `/providers/${page.slug}`;
  return {
    input: ({ url }) => {
      if (url.pathname === entry || url.pathname === `${entry}/`.replace('//', '/')) { url.pathname = inside; return url; }
      return undefined;
    },
    output: ({ url }) => {
      if (url.pathname === inside) { url.pathname = entry; return url; }
      return undefined;
    },
  };
}

/** A link from a page on another host back to the site (absolute), or the plain path on the site itself. */
export const siteHref = (page: PageHost | null | undefined, siteOrigin: string, path: string) => (page ? `${siteOrigin}${path}` : path);
