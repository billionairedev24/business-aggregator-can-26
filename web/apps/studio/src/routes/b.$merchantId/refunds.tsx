import { createFileRoute } from '@tanstack/react-router';
import { RefundsScreen } from '../../features/finance/RefundsScreen';

export const Route = createFileRoute('/b/$merchantId/refunds')({ component: RefundsScreen });
