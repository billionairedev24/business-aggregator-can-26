import { createFileRoute } from '@tanstack/react-router';
import { EarningsScreen } from '../../features/finance/EarningsScreen';

export const Route = createFileRoute('/b/$merchantId/earnings')({ component: EarningsScreen });
