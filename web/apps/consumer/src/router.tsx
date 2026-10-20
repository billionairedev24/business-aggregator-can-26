import './lib/zodJitless'; // first: before any zod schema is built (CSP without 'unsafe-eval', S104-09)
import { createRouter } from '@tanstack/react-router';
import { QueryClient } from '@tanstack/react-query';
import { setupRouterSsrQueryIntegration } from '@tanstack/react-router-ssr-query';
import { isNotFound, isUnauthorized, setHttpBase } from '@northline/client';
import { routeTree } from './routeTree.gen';
import { RouteError } from './features/shell/RouteError';
import { NotFound } from './features/shell/NotFound';
import { pageRewrite } from './lib/pages';
import { cspNonce, publicConfig } from './lib/request';

export interface RouterContext { queryClient: QueryClient }

/** A server-side call to the consumer-bff that has no answer by then fails (the page renders its unreachable state). */
const SSR_TIMEOUT_MS = 10_000;

/**
 * One router + QueryClient per request on the server, one per page in the browser (TanStack Start calls this).
 * Loaders `ensureQueryData(...)`; screens `useSuspenseQuery(...)` the same options; the SSR integration dehydrates
 * what the server fetched and hydrates it in the browser (docs/CONSUMER_WEB_PLAN.md § Data loading).
 */
export function getRouter() {
  if (import.meta.env.SSR) setHttpBase(process.env.NL_BFF_URL ?? 'http://localhost:8081', { timeoutMs: SSR_TIMEOUT_MS });
  const queryClient = new QueryClient({
    defaultOptions: {
      // On the server a failed call is not tried again: the page renders its error state at once (a BFF that is down
      // cost three tries and three seconds per query before) and the browser tries again with a backoff.
      queries: { staleTime: 30_000, retry: (n, e) => !import.meta.env.SSR && !isUnauthorized(e) && !isNotFound(e) && n < 2 },
      mutations: { retry: false },
    },
  });
  const router = createRouter({
    routeTree,
    // S-54: a business page on pages.<zone>/<slug> or the business's own domain is /providers/<slug> inside the app.
    rewrite: pageRewrite(publicConfig().page),
    context: { queryClient },
    defaultPreload: 'intent',
    defaultPreloadStaleTime: 0,
    scrollRestoration: true,
    defaultErrorComponent: RouteError,
    defaultNotFoundComponent: NotFound,
    // S104-09: the CSP has no 'unsafe-inline' for scripts; every inline script carries this page's nonce
    ssr: { nonce: cspNonce() },
  });
  setupRouterSsrQueryIntegration({ router, queryClient });
  return router;
}

declare module '@tanstack/react-router' { interface Register { router: ReturnType<typeof getRouter> } }
