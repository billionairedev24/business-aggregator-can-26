import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { PrivacyScreen } from '../../features/privacy/PrivacyScreen';

/** Privacy requests (S-105): access, correction and erasure, with each one's law and deadline; the retention schedule's report (S-107). */
export const Route = createFileRoute('/_console/privacy')({
  validateSearch: z.object({
    state: z.enum(['open', 'closed']).optional(), request: z.string().optional(), view: z.enum(['requests', 'retention']).optional(),
  }),
  component: PrivacyScreen,
});
