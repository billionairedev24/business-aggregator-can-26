import { createFileRoute } from '@tanstack/react-router';
import { OnCall } from '../../features/oncall/OnCall';

/** On-call & escalations (S-96): every staff member. */
export const Route = createFileRoute('/_console/on-call')({ component: OnCall });
