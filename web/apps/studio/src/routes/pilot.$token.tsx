import { createFileRoute, redirect } from '@tanstack/react-router';
import { testingToolsEnabled } from '@northline/ui';
import { requireSession } from '../features/shell/guards';
import { PilotInviteScreen } from '../features/onboarding/PilotInviteScreen';

/**
 * Pilot invite link (S-120, `northline.studio.base-url` + `/pilot/<token>`): sign in or register, then onboard. A pilot
 * tool (redesign § 3.5): only with the testing tools on (`VITE_NL_TESTING_TOOLS=1`); otherwise the link opens the
 * ordinary onboarding. The api's pilot-invite endpoints stay.
 */
export const Route = createFileRoute('/pilot/$token')({
  beforeLoad: async ({ context, location }) => {
    if (!testingToolsEnabled()) throw redirect({ to: '/onboarding' });
    return requireSession(context.queryClient, location.href);
  },
  component: PilotRoute,
});

function PilotRoute() {
  const { token } = Route.useParams();
  return <PilotInviteScreen token={token} />;
}
