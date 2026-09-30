import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Orders & bookings (S-58) — reached from the account menu only, never the top navigation. */
export const Route = createFileRoute('/account/orders')({ ...pending('orders') });
