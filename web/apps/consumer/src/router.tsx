import { createRouter } from '@tanstack/react-router';
import { QueryClient } from '@tanstack/react-query';
import { setupRouterSsrQueryIntegration } from '@tanstack/react-router-ssr-query';
import { isNotFound, isUnauthorized, setHttpBase } from '@northline/client';
import { routeTree } from './routeTree.gen';
import { RouteError } from './features/shell/RouteError';
import { NotFound } from './features/shell/NotFound';
import { pageRewrite } from './lib/pages';
import { publicConfig } from './lib/request';

export interface RouterContext { queryClient: QueryClient }

/**
 * One router + QueryClient per request on the server, one per page in the browser (TanStack Start calls this).
 * Loaders `ensureQueryData(...)`; screens `useSuspenseQuery(...)` the same options; the SSR integration dehydrates
 * what the server fetched and hydrates it in the browser (docs/CONSUMER_WEB_PLAN.md § Data loading).
 */
export function getRouter() {
  if (import.meta.env.SSR) setHttpBase(process.env.NL_BFF_URL ?? 'http://localhost:8081');
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: (n, e) => !isUnauthorized(e) && !isNotFound(e) && n < 2 },
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
  });
  setupRouterSsrQueryIntegration({ router, queryClient });
  return router;
}

declare module '@tanstack/react-router' { interface Register { router: ReturnType<typeof getRouter> } }
