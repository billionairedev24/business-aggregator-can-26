import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { CASE_VIEWS, Disputes, KINDS } from '../../features/disputes/Disputes';

/** Disputes & refunds (S-80, S-177): the state tab, the kind, province and market; a case opens `/disputes/$kind/$caseId`. */
export const Route = createFileRoute('/_console/disputes')({
  validateSearch: z.object({
    province: z.string().optional(), market: z.string().optional(),
    view: z.enum(CASE_VIEWS).optional().catch(undefined), kind: z.enum(KINDS).optional().catch(undefined),
  }),
  component: Disputes,
});
