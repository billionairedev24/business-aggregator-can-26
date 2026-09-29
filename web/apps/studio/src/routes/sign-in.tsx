import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../features/shell/ScreenPending';

export const Route = createFileRoute('/sign-in')({ component: () => <ScreenPending title="Sign in" /> });
