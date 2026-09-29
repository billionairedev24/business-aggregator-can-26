import { createFileRoute } from '@tanstack/react-router';
import { ReportsScreen } from '../../features/finance/ReportsScreen';

export const Route = createFileRoute('/b/$merchantId/reports')({ component: ReportsScreen });
