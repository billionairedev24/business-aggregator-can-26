import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { SupportDesk } from '../../features/support/SupportDesk';

/** Support desk (S-83): province and market of the region model, the queue's chip and the open case. */
export const Route = createFileRoute('/_console/support')({
  validateSearch: z.object({
    province: z.string().optional(), market: z.string().optional(), filter: z.string().optional(), ticket: z.string().optional(),
  }),
  component: SupportDesk,
});
