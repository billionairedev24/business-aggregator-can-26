import { createFileRoute } from '@tanstack/react-router';
import { requireSession } from '../features/shell/guards';
import { PilotInviteScreen } from '../features/onboarding/PilotInviteScreen';

/** Pilot invite link (S-120, `northline.studio.base-url` + `/pilot/<token>`): sign in or register, then onboard. */
export const Route = createFileRoute('/pilot/$token')({
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  component: PilotRoute,
});

function PilotRoute() {
  const { token } = Route.useParams();
  return <PilotInviteScreen token={token} />;
}
