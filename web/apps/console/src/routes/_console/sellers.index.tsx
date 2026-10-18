import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { SellersDirectory } from '../../features/sellers/SellersDirectory';

/** Sellers & providers (S-82): `?q=&province=&market=&risk=&seller=` (the open sheet). */
export const Route = createFileRoute('/_console/sellers/')({
  validateSearch: z.object({ q: z.string().optional(), province: z.string().optional(), market: z.string().optional(), risk: z.boolean().optional().catch(undefined), seller: z.string().optional() }),
  component: SellersDirectory,
});
