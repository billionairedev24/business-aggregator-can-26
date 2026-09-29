import { createFileRoute } from '@tanstack/react-router';
import { DashboardScreen } from '../../features/dashboard/DashboardScreen';
import { dashboardQuery } from '../../features/dashboard/api';

export const Route = createFileRoute('/b/$merchantId/')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(dashboardQuery(params.merchantId)); },
  component: DashboardScreen,
});
