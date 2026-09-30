import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Shop landing (S-49): a landing page, not search results. */
export const Route = createFileRoute('/shop/')({ ...pending('shop') });
