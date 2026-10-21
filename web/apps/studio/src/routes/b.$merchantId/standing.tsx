import { createFileRoute } from '@tanstack/react-router';
import { StandingScreen } from '../../features/standing/StandingScreen';
import { standingQuery } from '../../features/standing/api';

/** Studio › Standing (S-156): consequences with their reasons, appeals, coaching. */
export const Route = createFileRoute('/b/$merchantId/standing')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(standingQuery(params.merchantId)); },
  component: StandingScreen,
});
