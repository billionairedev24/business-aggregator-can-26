import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../../features/shell/ScreenPending';

export const Route = createFileRoute('/onboarding/')({ component: () => <ScreenPending title="Onboarding" /> });
