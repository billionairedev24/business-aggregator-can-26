import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../../../features/shell/ScreenPending';

export const Route = createFileRoute('/b/$merchantId/listings/')({ component: () => <ScreenPending title="Listings" /> });
