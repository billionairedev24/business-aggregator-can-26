import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { pending } from '../features/shell/pending';

/** Search results (S-48). `?q=` from the header / hero search; `scope` narrows to one side of the marketplace. */
export const SearchParams = z.object({
  q: z.string().optional().catch(undefined),
  scope: z.enum(['all', 'services', 'shop', 'food']).optional().catch(undefined),
});
export const Route = createFileRoute('/search')({ validateSearch: SearchParams, ...pending('search') });
