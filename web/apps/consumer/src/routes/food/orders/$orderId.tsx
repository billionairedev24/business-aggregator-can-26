import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Food order tracking (S-57): design 06 `foodTrack`. */
export const Route = createFileRoute('/food/orders/$orderId')({ ...pending('foodTrack') });
