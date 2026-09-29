import { createFileRoute } from '@tanstack/react-router';
import { requireSession } from '../features/shell/guards';
import { InviteAcceptScreen } from '../features/settings/InviteAcceptScreen';

/** Team invitation link (`northline.studio.base-url` + `/invite/<token>`): sign in or create an account, then join. */
export const Route = createFileRoute('/invite/$token')({
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  component: InviteRoute,
});

function InviteRoute() {
  const { token } = Route.useParams();
  return <InviteAcceptScreen token={token} />;
}
