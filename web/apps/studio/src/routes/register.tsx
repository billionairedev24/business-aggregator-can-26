import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { SignedOutPage } from '../features/auth/SignedOutPage';
import { federationContext, redirectIfSignedIn, safeNext, tabTarget, validateAuthSearch } from '../features/auth/routeSupport';

/** /register opens the signed-out page on the "Create account" tab (pre-filled when coming back from Google/Apple). */
export const Route = createFileRoute('/register')({
  validateSearch: validateAuthSearch,
  beforeLoad: ({ context, search }) => redirectIfSignedIn(context.queryClient, search),
  component: RegisterRoute,
});

function RegisterRoute() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  return (
    <SignedOutPage
      mode="register"
      next={safeNext(search.next)}
      prefill={{ firstName: search.firstName, lastName: search.lastName, email: search.email }}
      error={search.error}
      federation={federationContext(search)}
      onModeChange={(mode, opts) => void navigate(tabTarget(mode, search, opts?.recover) as never)}
    />
  );
}
