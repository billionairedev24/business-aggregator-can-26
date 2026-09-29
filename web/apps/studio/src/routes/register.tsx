import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../features/shell/ScreenPending';

export const Route = createFileRoute('/register')({ component: () => <ScreenPending title="Create account" /> });
