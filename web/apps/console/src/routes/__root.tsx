import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { ErrorState } from '@northline/ui';
import { NotFound } from '../features/shell/NotFound';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => <Outlet />,
  notFoundComponent: NotFound,
  errorComponent: ({ error, reset }) => <div style={{ padding: 32 }}><ErrorState message={error instanceof Error ? error.message : String(error)} onRetry={reset} /></div>,
});
