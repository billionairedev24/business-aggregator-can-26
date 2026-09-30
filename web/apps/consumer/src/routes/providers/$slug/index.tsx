import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Public provider page (S-54) from the storefront API; SEO, structured data (S-63). */
export const Route = createFileRoute('/providers/$slug/')({ ...pending('provider') });
