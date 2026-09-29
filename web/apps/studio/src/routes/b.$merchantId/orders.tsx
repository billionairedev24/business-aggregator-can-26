import { createFileRoute } from '@tanstack/react-router';
import { OrdersScreen } from '../../features/orders/OrdersScreen';
import { ordersQuery } from '../../features/orders/api';

export const Route = createFileRoute('/b/$merchantId/orders')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(ordersQuery(params.merchantId)); },
  component: OrdersScreen,
});
