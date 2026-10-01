import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Disputes } from '../../features/disputes/Disputes';

/** Disputes & refunds (S-80): province and market of the region model, and the open case (`kind:id`). */
export const Route = createFileRoute('/_console/disputes')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), case: z.string().optional() }),
  component: Disputes,
});
