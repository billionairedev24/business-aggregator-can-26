import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Overview } from '../../features/overview/Overview';

/** The overview (S-91), filtered by province and market of the region model. */
export const Route = createFileRoute('/_console/')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional() }),
  component: Overview,
});
