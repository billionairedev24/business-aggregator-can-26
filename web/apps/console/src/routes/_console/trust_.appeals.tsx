import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Appeals } from '../../features/appeals/Appeals';

/** Trust & safety › Appeals (S-156): province and market of the region model, and the open appeal. */
export const Route = createFileRoute('/_console/trust_/appeals')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), appeal: z.string().optional() }),
  component: Appeals,
});
