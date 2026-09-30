import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { SignedOutPage } from '../features/auth/SignedOutPage';
import { federationContext, redirectIfSignedIn, safeNext, tabTarget, validateAuthSearch } from '../features/auth/routeSupport';

export const Route = createFileRoute('/sign-in')({
  validateSearch: validateAuthSearch,
  beforeLoad: ({ context, search }) => redirectIfSignedIn(context.queryClient, search),
  component: SignInRoute,
});

function SignInRoute() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  return (
    <SignedOutPage
      mode="signin"
      next={safeNext(search.next)}
      resumeIdentifier={search.step === 'factor' ? search.identifier : undefined}
      recoverOnLoad={search.recover === '1'}
      error={search.error}
      federation={federationContext(search)}
      onModeChange={(mode, opts) => void navigate(tabTarget(mode, search, opts?.recover) as never)}
    />
  );
}
