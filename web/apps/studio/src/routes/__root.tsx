import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { ErrorState, PageState } from '@northline/ui';
import { NotFound } from '../features/shell/NotFound';
import { useShellT } from '../features/shell/messages';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  errorComponent: function RootError({ error, reset }) {
    const t = useShellT();
    return <PageState title={`Northline ${t('studio')}`}><ErrorState message={error instanceof Error ? error.message : String(error)} onRetry={reset} /></PageState>;
  },
});
