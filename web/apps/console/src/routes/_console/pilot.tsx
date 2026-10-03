import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { PilotBoard } from '../../features/pilot/PilotBoard';

/** Pilot onboarding (S-120): a market's pilot businesses from invite to live. */
export const Route = createFileRoute('/_console/pilot')({
  validateSearch: z.object({ market: z.string().optional(), pilot: z.string().optional() }),
  component: PilotBoard,
});
