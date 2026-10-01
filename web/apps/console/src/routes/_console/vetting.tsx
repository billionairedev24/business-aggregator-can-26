import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { ListingVetting } from '../../features/vetting/ListingVetting';

/** Listing vetting (S-92): province and market of the region model. */
export const Route = createFileRoute('/_console/vetting')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional() }),
  component: ListingVetting,
});
