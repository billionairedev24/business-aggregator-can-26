import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Services landing (S-53): categories, service-type aware. */
export const Route = createFileRoute('/services/')({ ...pending('services') });
