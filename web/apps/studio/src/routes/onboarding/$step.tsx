import { createFileRoute, notFound, redirect } from '@tanstack/react-router';
import { requireSession } from '../../features/shell/guards';
import { OnboardingScreen } from '../../features/onboarding/OnboardingScreen';
import { isLegacyStep, isStep, validateOnboardingSearch } from '../../features/onboarding/model';

/**
 * `/onboarding/$step?m=<merchantId>&type=…` — account · business · categories · verification · review (the approval
 * gate). The old `page` and `listings` steps moved into the Studio after approval: their links open the gate, which
 * opens the Studio once the business is approved.
 */
export const Route = createFileRoute('/onboarding/$step')({
  validateSearch: validateOnboardingSearch,
  beforeLoad: ({ context, location, params, search }) => {
    if (isLegacyStep(params.step)) throw redirect({ to: '/onboarding/$step', params: { step: 'review' }, search, replace: true });
    if (!isStep(params.step)) throw notFound();
    return requireSession(context.queryClient, location.href);
  },
  component: function OnboardingStepRoute() {
    const { step } = Route.useParams();
    const search = Route.useSearch();
    return isStep(step) ? <OnboardingScreen step={step} search={search} /> : null;
  },
});
