import { createFileRoute } from '@tanstack/react-router';
import { ScreenPending } from '../../features/shell/ScreenPending';

export const Route = createFileRoute('/_console/provinces')({ component: () => <ScreenPending screen="regions" /> });
