import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { UatScreen } from '../../features/uat/UatScreen';

/** Pilot UAT (S-121): feedback triage, participants' sign-offs, the go/no-go report. */
export const Route = createFileRoute('/_console/uat')({
  validateSearch: z.object({
    view: z.enum(['feedback', 'participants', 'report']).optional(), state: z.string().optional(), blocking: z.enum(['yes', 'no']).optional(),
    persona: z.string().optional(), item: z.string().optional(),
  }),
  component: UatScreen,
});
