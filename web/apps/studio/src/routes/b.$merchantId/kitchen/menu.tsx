import { createFileRoute } from '@tanstack/react-router';
import { MenuBuilderScreen } from '../../../features/kitchen/MenuBuilderScreen';
import { groupsQuery, menusQuery } from '../../../features/kitchen/api';

export const Route = createFileRoute('/b/$merchantId/kitchen/menu')({
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(menusQuery(params.merchantId));
    void context.queryClient.prefetchQuery(groupsQuery(params.merchantId));
  },
  component: MenuBuilderScreen,
});
