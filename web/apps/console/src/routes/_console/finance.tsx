import { createFileRoute } from '@tanstack/react-router';
import { FinanceScreen } from '../../features/finance/FinanceScreen';

/** Finance (S-85): escrow, payouts, revenue, take by tier, the Stripe ↔ ledger reconciliation, tax. */
export const Route = createFileRoute('/_console/finance')({ component: FinanceScreen });
