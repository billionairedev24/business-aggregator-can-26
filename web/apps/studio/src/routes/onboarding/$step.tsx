import { createFileRoute, notFound } from '@tanstack/react-router';
import { requireSession } from '../../features/shell/guards';
import { OnboardingScreen } from '../../features/onboarding/OnboardingScreen';
import { isStep, validateOnboardingSearch } from '../../features/onboarding/model';

/** `/onboarding/$step?m=<merchantId>&type=…` — account · business · verification · review · page · listings. */
export const Route = createFileRoute('/onboarding/$step')({
  validateSearch: validateOnboardingSearch,
  beforeLoad: ({ context, location, params }) => {
    if (!isStep(params.step)) throw notFound();
    return requireSession(context.queryClient, location.href);
  },
  component: function OnboardingStepRoute() {
    const { step } = Route.useParams();
    const search = Route.useSearch();
    return isStep(step) ? <OnboardingScreen step={step} search={search} /> : null;
  },
});
