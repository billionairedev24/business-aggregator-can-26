import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../../features/shell/ScreenPending';

export const Route = createFileRoute('/_console/sellers/')({ component: () => <ScreenPending screen="sellers" /> });
