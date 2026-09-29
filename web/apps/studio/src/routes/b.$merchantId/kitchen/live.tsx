import { createFileRoute } from '@tanstack/react-router';
import { LiveOrdersScreen } from '../../../features/kitchen/LiveOrdersScreen';
import { liveQuery } from '../../../features/kitchen/api';

export const Route = createFileRoute('/b/$merchantId/kitchen/live')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(liveQuery(params.merchantId)); },
  component: LiveOrdersScreen,
});
