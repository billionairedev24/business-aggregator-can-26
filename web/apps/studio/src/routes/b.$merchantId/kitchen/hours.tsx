import { createFileRoute } from '@tanstack/react-router';
import { HoursScreen } from '../../../features/kitchen/HoursScreen';
import { setupQuery } from '../../../features/kitchen/api';

export const Route = createFileRoute('/b/$merchantId/kitchen/hours')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(setupQuery(params.merchantId)); },
  component: HoursScreen,
});
