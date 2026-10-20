import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { FailureScreen } from '@northline/ui';
import { failureKind } from '../lib/http';
import { NotFound } from '../features/shell/NotFound';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  // What escapes every route: the branded screen — the console-bff or the api not answering is tried again on its own
  // with a backoff — never the raw message.
  errorComponent: function RootError({ error, reset }) {
    return (
      <main id="main" className="nl-failure-page">
        <FailureScreen kind={failureKind(error)} onRetry={reset} autoRetry retryKey={error}
          detail={import.meta.env.DEV && error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : null} />
      </main>
    );
  },
});
