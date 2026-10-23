import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { OrdersScreen } from '../../features/orders/OrdersScreen';
import { ordersQuery } from '../../features/orders/api';

export const Route = createFileRoute('/b/$merchantId/orders')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(ordersQuery(params.merchantId)); },
  // `?q=` — the shell's global search (E-15) opens the table already searched
  validateSearch: z.object({ q: z.string().optional() }),
  component: function OrdersRoute() { return <OrdersScreen search={Route.useSearch().q} />; },
});
