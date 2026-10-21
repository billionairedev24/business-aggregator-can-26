import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { TrustSafety } from '../../features/trust/TrustSafety';

/** Trust & safety (S-93): province and market of the region model. */
export const Route = createFileRoute('/_console/trust')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), view: z.enum(['ai', 'scan']).optional() }),
  component: TrustSafety,
});
