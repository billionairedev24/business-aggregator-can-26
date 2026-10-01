import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { VerificationQueue } from '../../features/verify/VerificationQueue';

/** The verification queue (S-79): province and market of the region model, and the open application. */
export const Route = createFileRoute('/_console/verification')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), application: z.string().optional() }),
  component: VerificationQueue,
});
