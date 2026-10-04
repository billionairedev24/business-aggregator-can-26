import { useEffect, type ReactNode } from 'react';
import { createRootRouteWithContext, HeadContent, Outlet, Scripts, useRouter } from '@tanstack/react-router';
import { I18nProvider, SiteLinkProvider } from '@northline/ui';
import { configureAuthOrigin } from '@northline/auth-kit';
import tokensCss from '@northline/tokens/tokens.css?url';
import uiCss from '@northline/ui/styles.css?url';
import shellCss from '../features/shell/shell.css?url';
import { ConsumerLayout } from '../features/shell/ConsumerLayout';
import { NotFound } from '../features/shell/NotFound';
import { RouteError } from '../features/shell/RouteError';
import { htmlLang, persistLocale, urlLocale } from '../lib/locale';
import { publicConfig, requestLocale } from '../lib/request';
import { RouterSiteLink } from '../lib/SiteLinkAdapter';
import type { RouterContext } from '../router';

const FONTS = 'https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;0,6..72,600;1,6..72,400&family=Instrument+Sans:wght@400;500;600;700&display=swap';

export const Route = createRootRouteWithContext<RouterContext>()({
  beforeLoad: () => ({ locale: requestLocale(), config: publicConfig() }),
  head: () => ({
    meta: [{ charSet: 'utf-8' }, { name: 'viewport', content: 'width=device-width, initial-scale=1' }, { title: 'Northline' }],
    links: [
      { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
      { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
      { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossOrigin: 'anonymous' },
      { rel: 'stylesheet', href: FONTS },
      { rel: 'stylesheet', href: tokensCss },
      { rel: 'stylesheet', href: uiCss },
      { rel: 'stylesheet', href: shellCss },
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
  const { locale, config } = Route.useRouteContext();
  configureAuthOrigin(config.authOrigin); // idempotent; server and browser render the same auth links
  // S-63: arriving on a `?lang=` URL (a search engine's French result) keeps that language for the next pages
  useEffect(() => { if (urlLocale(window.location.href)) persistLocale(locale); }, [locale]);
  return (
    <I18nProvider initial={locale} onChange={persistLocale}>
      <SiteLinkProvider value={RouterSiteLink}>
        <ConsumerLayout>
          <Outlet />
        </ConsumerLayout>
      </SiteLinkProvider>
    </I18nProvider>
  );
}
