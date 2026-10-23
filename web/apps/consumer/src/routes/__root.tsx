import { useEffect, type ReactNode } from 'react';
import { createRootRouteWithContext, HeadContent, Outlet, Scripts, useRouter } from '@tanstack/react-router';
import { I18nProvider, SiteLinkProvider, brand, configurePlatformTimeZone } from '@northline/ui';
import { configureAuthOrigin } from '@northline/auth-kit';
import { configurePhotoBase } from '@northline/media';
import tokensCss from '@northline/tokens/tokens.css?url';
import uiCss from '@northline/ui/styles.css?url';
import shellCss from '../features/shell/shell.css?url';
import locationCss from '../features/location/location.css?url';
import artCss from '../features/art/art.css?url';
import pagesCss from '../features/pages/pages.css?url';
import { ConsumerLayout } from '../features/shell/ConsumerLayout';
import { NotFound } from '../features/shell/NotFound';
import { RouteError } from '../features/shell/RouteError';
import { htmlLang, persistLocale, urlLocale } from '../lib/locale';
import { publicConfig, requestLocale } from '../lib/request';
import { RouterSiteLink } from '../lib/SiteLinkAdapter';
import { RenderClockProvider } from '../lib/renderClock';
import { regionsQuery } from '../features/location/regions';
import type { RouterContext } from '../router';

export const Route = createRootRouteWithContext<RouterContext>()({
  beforeLoad: () => ({ locale: requestLocale(), config: publicConfig() }),
  // Engineering follow-ups (S-117 finding): the region model gives every page its market's time zone. Fetched here, it
  // is in the server's render and in the state the browser hydrates from; fetched by the page's useQuery only, the
  // server rendered times in UTC and the browser in the market's zone — a hydration mismatch on every product page.
  // prefetchQuery never throws: without the api the page still renders (in the platform zone, on both sides).
  // S-182: `renderedAt` is the one clock of the server's render and the browser's first render (lib/renderClock.tsx).
  loader: async ({ context }) => {
    await context.queryClient.prefetchQuery(regionsQuery(context.locale));
    return { renderedAt: Date.now() };
  },
  head: () => ({
    meta: [{ charSet: 'utf-8' }, { name: 'viewport', content: 'width=device-width, initial-scale=1' }, { title: brand.name }],
    links: [
      { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
      // the tokens carry the palette's self-hosted fonts (@font-face → /assets/*.woff2; nothing from Google)
      { rel: 'stylesheet', href: tokensCss },
      { rel: 'stylesheet', href: uiCss },
      { rel: 'stylesheet', href: shellCss },
      { rel: 'stylesheet', href: locationCss },
      // the illustrated scenes (features/art): category, department and cuisine pictures on every landing page
      { rel: 'stylesheet', href: artCss },
      // the shared page parts (features/pages): rails, tiles, landing bands, breadcrumbs, sticky phone actions
      { rel: 'stylesheet', href: pagesCss },
    ],
  }),
  shellComponent: Document,
  component: App,
  notFoundComponent: NotFound,
  errorComponent: RouteError,
});

/** The HTML document (server-rendered): language from the cookie / Accept-Language, public config for the browser. */
function Document({ children }: { children: ReactNode }) {
  const { locale, config } = Route.useRouteContext();
  const nonce = useRouter().options.ssr?.nonce; // S104-09: the page's CSP nonce (router.tsx)
  return (
    <html lang={htmlLang(locale)}>
      <head>
        <HeadContent />
        <script nonce={nonce} suppressHydrationWarning dangerouslySetInnerHTML={{ __html: `window.__NL_CONFIG__=${JSON.stringify(config).replace(/</g, '\\u003c')}` }} />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

function App() {
  const { locale, config, queryClient } = Route.useRouteContext();
  const { renderedAt } = Route.useLoaderData() ?? {};
  // S-182: the platform zone from the region model the page was rendered with, before anything below formats a time.
  // regionsQuery's own call only runs where the query is fetched (the server); the browser gets the data hydrated,
  // so it kept UTC while the server wrote the market's zone — "Order by 5:20 p.m." became "11:20 p.m." (React error 418).
  configurePlatformTimeZone(queryClient.getQueryData(regionsQuery(locale).queryKey)?.platformTimeZone);
  configureAuthOrigin(config.authOrigin); // idempotent; server and browser render the same auth links
  configurePhotoBase(config.photosBaseUrl); // the stock photos' CDN when set (NL_PHOTOS_BASE_URL), else /photos/
  // S-63: arriving on a `?lang=` URL (a search engine's French result) keeps that language for the next pages
  useEffect(() => { if (urlLocale(window.location.href)) persistLocale(locale); }, [locale]);
  return (
    <I18nProvider initial={locale} onChange={persistLocale}>
      <SiteLinkProvider value={RouterSiteLink}>
        <RenderClockProvider at={renderedAt}>
          <ConsumerLayout>
            <Outlet />
          </ConsumerLayout>
        </RenderClockProvider>
      </SiteLinkProvider>
    </I18nProvider>
  );
}
