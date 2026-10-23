import { createFileRoute } from '@tanstack/react-router';
import { TicketPage } from '../../features/support/SupportDesk';

/** One support case (S-177): `/support/<ticket id>`. */
export const Route = createFileRoute('/_console/support_/$ticketId')({ component: TicketPage });
