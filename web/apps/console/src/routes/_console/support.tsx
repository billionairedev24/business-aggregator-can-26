import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';
import { SupportDesk } from '../../features/support/SupportDesk';

/** Support desk (S-83): province and market of the region model and the queue's chip; a case opens `/support/$ticketId`,
 * and the old sheet's `?ticket=<id>` redirects there (S-180). */
export const Route = createFileRoute('/_console/support')({
  validateSearch: z.object({
    province: z.string().optional(), market: z.string().optional(), filter: z.string().optional(), ticket: z.string().optional(),
  }),
  beforeLoad: ({ search }) => {
    if (search.ticket) throw redirect({ to: '/support/$ticketId', params: { ticketId: search.ticket }, replace: true });
  },
  component: SupportDesk,
});
