import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { ErrorState, PageState } from '@northline/ui';
import { useShellT } from '../features/shell/messages';
import { NotFound } from '../features/shell/NotFound';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  errorComponent: function RootError({ error, reset }) {
    const t = useShellT();
    return <PageState title={`Northline ${t('console')}`}><ErrorState message={error instanceof Error ? error.message : String(error)} onRetry={reset} /></PageState>;
  },
});
