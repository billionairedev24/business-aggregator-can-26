import { createFileRoute } from '@tanstack/react-router';
import { DashboardScreen } from '../../features/dashboard/DashboardScreen';
import { dashboardQuery } from '../../features/dashboard/api';

export const Route = createFileRoute('/b/$merchantId/')({
  // S-69: the landing screen's loader stays in the initial bundle, so its data is requested while the screen's chunk
  // downloads (other routes' loaders travel with their component — vite.config.ts).
  codeSplitGroupings: [['component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']],
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(dashboardQuery(params.merchantId)); },
  component: DashboardScreen,
});
