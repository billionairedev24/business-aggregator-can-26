import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';
import { DEPTS, SELLER_VIEWS, SellersDirectory } from '../../features/sellers/SellersDirectory';

/**
 * Sellers & providers (S-82, S-177): `?view=&dept=&risk=&expiring=&q=&province=&market=`; a row opens `/sellers/$sellerId`.
 * S-180: the old sheet's address `?seller=<id>` (bookmarks, links in tickets) opens that seller's page.
 */
export const Route = createFileRoute('/_console/sellers/')({
  validateSearch: z.object({
    q: z.string().optional(), province: z.string().optional(), market: z.string().optional(),
    risk: z.boolean().optional().catch(undefined), expiring: z.boolean().optional().catch(undefined),
    view: z.enum(SELLER_VIEWS).optional().catch(undefined), dept: z.enum(DEPTS).optional().catch(undefined),
    seller: z.string().optional(),
  }),
  beforeLoad: ({ search }) => {
    if (search.seller) throw redirect({ to: '/sellers/$sellerId', params: { sellerId: search.seller }, replace: true });
  },
  component: SellersDirectory,
});
