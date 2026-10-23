import { createFileRoute } from '@tanstack/react-router';
import { DisputePage } from '../../features/disputes/DisputePage';

/** A dispute or refund case (S-177): `/disputes/dispute/D1`, `/disputes/refund/R1`. */
export const Route = createFileRoute('/_console/disputes_/$kind/$caseId')({ component: DisputePage });
