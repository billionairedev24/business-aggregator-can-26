import { createFileRoute } from '@tanstack/react-router';
import { PayoutsScreen } from '../../features/finance/PayoutsScreen';

export const Route = createFileRoute('/b/$merchantId/payouts')({ component: PayoutsScreen });
