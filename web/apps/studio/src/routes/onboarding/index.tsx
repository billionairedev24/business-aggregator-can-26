import { createFileRoute } from '@tanstack/react-router';
import { requireSession } from '../../features/shell/guards';
import { OnboardingEntry } from '../../features/onboarding/OnboardingScreen';
import { validateOnboardingSearch } from '../../features/onboarding/model';

/** `/onboarding[?type=provider|seller|kitchen|both]` — Account step; type picker when no type is given. */
export const Route = createFileRoute('/onboarding/')({
  validateSearch: validateOnboardingSearch,
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  component: function OnboardingIndex() {
    return <OnboardingEntry search={Route.useSearch()} />;
  },
});
