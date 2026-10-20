import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { SignedOutPage } from '../features/auth/SignedOutPage';
import { redirectIfSignedIn, safeNext, tabTarget, validateAuthSearch } from '../features/auth/routeSupport';

/** S-154: "Forgot password?" — the sign-in tab on the reset form (the "you already have an account" email links here). */
export const Route = createFileRoute('/forgot-password')({
  validateSearch: validateAuthSearch,
  beforeLoad: ({ context, search }) => redirectIfSignedIn(context.queryClient, search),
  component: ForgotPasswordRoute,
});

function ForgotPasswordRoute() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  return (
    <SignedOutPage mode="signin" forgotOnLoad next={safeNext(search.next)} resumeIdentifier={search.email}
      onModeChange={(mode, opts) => void navigate(tabTarget(mode, search, opts?.recover) as never)} />
  );
}
