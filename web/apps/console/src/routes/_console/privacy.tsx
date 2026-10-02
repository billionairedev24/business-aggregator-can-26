import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { PrivacyQueue } from '../../features/privacy/PrivacyQueue';

/** Privacy requests (S-105): access, correction and erasure, with each one's law and deadline. */
export const Route = createFileRoute('/_console/privacy')({
  validateSearch: z.object({ state: z.enum(['open', 'closed']).optional(), request: z.string().optional() }),
  component: PrivacyQueue,
});
