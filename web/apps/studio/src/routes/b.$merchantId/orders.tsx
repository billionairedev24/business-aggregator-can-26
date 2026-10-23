import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { OrdersScreen } from '../../features/orders/OrdersScreen';
import { ordersQuery } from '../../features/orders/api';

export const Route = createFileRoute('/b/$merchantId/orders')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(ordersQuery(params.merchantId)); },
  // `?q=` — the shell's global search (E-15) opens the table already searched; `?view=` — the status tab (S-175)
  validateSearch: z.object({ q: z.string().optional(), view: z.string().optional() }),
  component: function OrdersRoute() {
    const { q, view } = Route.useSearch();
    const navigate = Route.useNavigate();
    return <OrdersScreen search={q} view={view} onViewChange={v => void navigate({ search: s => ({ ...s, view: v === 'all' ? undefined : v }), replace: true })} />;
  },
});
