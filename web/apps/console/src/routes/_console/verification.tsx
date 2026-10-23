import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { APP_VIEWS, VerificationQueue } from '../../features/verify/VerificationQueue';

/** The verification queue (S-79, S-177): the stage tab, high risk, province and market; S-158 `view=registry`. An application opens `/verification/$applicationId`. */
export const Route = createFileRoute('/_console/verification')({
  validateSearch: z.object({
    province: z.string().optional(), market: z.string().optional(), view: z.enum(['registry']).optional(),
    stage: z.enum(APP_VIEWS).optional().catch(undefined), risk: z.boolean().optional().catch(undefined),
  }),
  component: VerificationQueue,
});
