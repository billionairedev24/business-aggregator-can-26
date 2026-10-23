import { createFileRoute } from '@tanstack/react-router';
import { OrderPage } from '../../features/orders/OrderPage';

/** An order or booking by its reference (S-177): `/orders/NL-48188`. */
export const Route = createFileRoute('/_console/orders_/$orderRef')({ component: OrderPage });
