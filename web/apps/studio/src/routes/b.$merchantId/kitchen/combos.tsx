import { createFileRoute } from '@tanstack/react-router';
import { CombosScreen } from '../../../features/kitchen/CombosScreen';
import { combosQuery, groupsQuery, promosQuery } from '../../../features/kitchen/api';

export const Route = createFileRoute('/b/$merchantId/kitchen/combos')({
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(groupsQuery(params.merchantId));
    void context.queryClient.prefetchQuery(combosQuery(params.merchantId));
    void context.queryClient.prefetchQuery(promosQuery(params.merchantId));
  },
  component: CombosScreen,
});
