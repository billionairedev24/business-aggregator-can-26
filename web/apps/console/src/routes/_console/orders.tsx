import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { OrdersMonitor } from '../../features/orders/OrdersMonitor';
import { VIEWS } from '../../features/orders/api';

/** Orders & bookings (S-81): `?view=attention|live|escrow|late|all&q=&province=&market=`. */
export const Route = createFileRoute('/_console/orders')({
  validateSearch: z.object({ view: z.enum(VIEWS).optional().catch(undefined), q: z.string().optional(), province: z.string().optional(), market: z.string().optional() }),
  component: OrdersMonitor,
});
