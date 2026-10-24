import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';
import { CASE_VIEWS, Disputes, KINDS } from '../../features/disputes/Disputes';

/** Disputes & refunds (S-80, S-177): the state tab, the kind, province and market; a case opens `/disputes/$kind/$caseId`;
 * the old sheet's `?case=<kind>:<id>` redirects there (S-180). */
export const Route = createFileRoute('/_console/disputes')({
  validateSearch: z.object({
    province: z.string().optional(), market: z.string().optional(),
    view: z.enum(CASE_VIEWS).optional().catch(undefined), kind: z.enum(KINDS).optional().catch(undefined),
    case: z.string().optional(),
  }),
  beforeLoad: ({ search }) => {
    const [kind, caseId] = (search.case ?? '').split(':');
    if ((kind === 'dispute' || kind === 'refund') && caseId) throw redirect({ to: '/disputes/$kind/$caseId', params: { kind, caseId }, replace: true });
  },
  component: Disputes,
});
