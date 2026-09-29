import { createFileRoute } from '@tanstack/react-router';
import { AppointmentsScreen } from '../../features/appointments/AppointmentsScreen';
import { quoteRequestsQuery } from '../../features/appointments/api';

export const Route = createFileRoute('/b/$merchantId/appointments')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(quoteRequestsQuery(params.merchantId)); },
  component: AppointmentsScreen,
});
