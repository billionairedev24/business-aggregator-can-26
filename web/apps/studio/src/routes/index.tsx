import { createFileRoute, redirect } from '@tanstack/react-router';
import { businessesQuery } from '../features/shell/api';
import { requireSession } from '../features/shell/guards';
import { homeScreen, screenHref } from '../features/shell/nav';

/** Entry: signed out → sign in; no business yet → onboarding; otherwise the first business's home screen. */
export const Route = createFileRoute('/')({
  beforeLoad: async ({ context, location }) => {
    await requireSession(context.queryClient, location.href);
    const businesses = await context.queryClient.ensureQueryData(businessesQuery);
    const first = businesses[0];
    if (!first) throw redirect({ to: '/onboarding' });
    throw redirect({ to: screenHref(first.id, homeScreen(first.type)) });
  },
});
