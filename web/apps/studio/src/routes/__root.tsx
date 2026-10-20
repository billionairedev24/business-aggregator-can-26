import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import { brand } from '@northline/tokens';
import type { QueryClient } from '@tanstack/react-query';
import { FailureScreen } from '@northline/ui';
import { NotFound } from '../features/shell/NotFound';
import { failureKind } from '../lib/http';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  // What escapes every route: the branded screen — the studio-bff or the api not answering is tried again on its own
  // with a backoff — never the raw message (which showed "Internal Server Error" or a schema's issues).
  errorComponent: function RootError({ error, reset }) {
    return (
      <main id="main" className="nl-failure-page">
        <FailureScreen kind={failureKind(error)} onRetry={reset} autoRetry retryKey={error} supportHref={`mailto:${brand.supportEmail}`}
          detail={import.meta.env.DEV && error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : null} />
      </main>
    );
  },
});
