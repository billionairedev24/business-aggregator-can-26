import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { GoLive } from '../../features/golive/GoLive';

/** Go-live (S-118): a market's checklist, the two-person switch to live, rollback and hypercare. */
export const Route = createFileRoute('/_console/go-live')({
  validateSearch: z.object({ market: z.string().optional() }),
  component: GoLive,
});
