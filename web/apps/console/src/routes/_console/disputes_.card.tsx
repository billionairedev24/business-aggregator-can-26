import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { CardDisputes } from '../../features/disputes/CardDisputes';

/** Disputes › Card disputes (S-156): province and market of the region model, and the open card dispute. */
export const Route = createFileRoute('/_console/disputes_/card')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), dispute: z.string().optional() }),
  component: CardDisputes,
});
