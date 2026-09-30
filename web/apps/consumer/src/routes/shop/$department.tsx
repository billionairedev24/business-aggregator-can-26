import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Department / category page (S-49): design 06 `category`. */
export const Route = createFileRoute('/shop/$department')({ ...pending('category') });
