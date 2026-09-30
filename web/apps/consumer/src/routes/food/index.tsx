import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Food landing (S-57): kitchens open now, cuisines. */
export const Route = createFileRoute('/food/')({ ...pending('food') });
