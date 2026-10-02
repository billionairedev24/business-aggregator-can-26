import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Profile } from '../../features/profile/Profile';

/** My profile (design 03 `profile`): tabs security · sessions · audit ("My audit trail") · prefs — S-96. */
export const Route = createFileRoute('/_console/profile')({
  validateSearch: z.object({ tab: z.enum(['security', 'sessions', 'audit', 'prefs']).optional() }),
  component: Profile,
});
