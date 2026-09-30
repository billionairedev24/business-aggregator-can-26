import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Order confirmed and tracking (S-52): design 06 `confirmed`, live ETA over SSE. */
export const Route = createFileRoute('/orders/$orderId')({ ...pending('confirmed') });
