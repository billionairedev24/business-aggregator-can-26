import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { ScreenPending } from '../../features/shell/ScreenPending';

/** My profile (design 03 `profile`): tabs security · sessions · audit ("My audit trail") · prefs — S-96. */
export const Route = createFileRoute('/_console/profile')({
  validateSearch: z.object({ tab: z.enum(['security', 'sessions', 'audit', 'prefs']).optional() }),
  component: () => <ScreenPending screen="profile" />,
});
