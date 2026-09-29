import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../../../features/shell/ScreenPending';

export const Route = createFileRoute('/b/$merchantId/kitchen/hours')({ component: () => <ScreenPending title="Hours, prep & capacity" /> });
