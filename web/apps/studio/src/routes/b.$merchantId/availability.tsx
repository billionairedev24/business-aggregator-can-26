import { createFileRoute } from '@tanstack/react-router';
import { AvailabilityScreen } from '../../features/availability/AvailabilityScreen';
import { hoursQuery, rulesQuery } from '../../features/availability/api';

export const Route = createFileRoute('/b/$merchantId/availability')({
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(hoursQuery(params.merchantId));
    void context.queryClient.prefetchQuery(rulesQuery(params.merchantId));
  },
  component: AvailabilityScreen,
});
