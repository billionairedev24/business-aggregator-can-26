import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import { brand } from '@northline/tokens';
import type { QueryClient } from '@tanstack/react-query';
import { ErrorState } from '@northline/ui';
import { useShellT } from '../features/shell/messages';
import { NotFound, Standalone } from '../features/shell/NotFound';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  errorComponent: function RootError({ error, reset }) {
    const t = useShellT();
    return <Standalone><h1>{`${brand.name} ${t('console')}`}</h1><ErrorState message={error instanceof Error ? error.message : String(error)} onRetry={reset} /></Standalone>;
  },
});
