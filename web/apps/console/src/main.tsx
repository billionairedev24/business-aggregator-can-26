import './lib/zodJitless'; // first: before any zod schema is built (CSP without 'unsafe-eval', S104-09)
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { configurePlatformTimeZone, I18nProvider, type Locale } from '@northline/ui';
import '@northline/tokens/tokens.css';
import '@northline/ui/styles.css';
import './console.css';
import { createConsoleRouter } from './router';
import { isUnauthorized, setRequestLocale } from './lib/http';
import './lib/auth-server'; // configures northline-auth's origin for @northline/auth-kit

const fonts = document.getElementById('nl-fonts') as HTMLLinkElement | null;
if (fonts) {
  const sheet = document.createElement('link');
  sheet.rel = 'stylesheet';
  sheet.href = fonts.href;
  document.head.append(sheet);
}

// The platform zone until the region model answers (GET /api/v1/geo/regions → platformTimeZone; S-134).
configurePlatformTimeZone(import.meta.env.VITE_NL_PLATFORM_TIME_ZONE);

const LOCALE_KEY = 'nl.locale';
const initialLocale = (): Locale => { try { const v = localStorage.getItem(LOCALE_KEY); if (v === 'fr' || v === 'en') return v; } catch { /* ignore */ } return navigator.language.toLowerCase().startsWith('fr') ? 'fr' : 'en'; };

const locale = initialLocale();
setRequestLocale(locale);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: (n, e) => !isUnauthorized(e) && n < 2 },
    mutations: { retry: false },
  },
});
const router = createConsoleRouter(queryClient);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider initial={locale} onChange={l => { setRequestLocale(l); document.documentElement.lang = l === 'fr' ? 'fr-CA' : 'en-CA'; try { localStorage.setItem(LOCALE_KEY, l); } catch { /* ignore */ } }}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nProvider>
  </StrictMode>,
);
