import { useState } from 'react';
import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { Alert, Button } from '@northline/ui';
import { ResetWithLink } from '@northline/auth-kit';
import { SignedOutPage } from '../features/auth/SignedOutPage';
import { useAuthT } from '../features/auth/messages';
import { validateAuthSearch } from '../features/auth/routeSupport';

/** S-154: the password-reset email's button — a new password; then sign in (the second factor is still asked). */
export const Route = createFileRoute('/reset-password')({
  validateSearch: (raw: Record<string, unknown>) => ({ ...validateAuthSearch(raw), token: typeof raw.token === 'string' ? raw.token : undefined }),
  component: ResetPasswordRoute,
});

function ResetPasswordRoute() {
  const { token } = Route.useSearch();
  const t = useAuthT();
  const navigate = useNavigate();
  const [email, setEmail] = useState<string | null>(null);
  const signIn = (identifier?: string) => void navigate({ to: '/sign-in', search: (identifier ? { step: 'password', identifier } : {}) as never });
  const content = !token ? <Alert tone="error">{t('linkExpired')}</Alert>
    : email !== null ? (
      <>
        <Alert tone="info" role="status">{t('resetDone')}</Alert>
        <Button className="nl-auth-primary" onClick={() => signIn(email)}>{t('resetSignIn')}</Button>
      </>
    )
    : <ResetWithLink token={token} onDone={setEmail} />;
  return <SignedOutPage mode="signin" onModeChange={() => signIn()} panel={{ title: t('forgotTitle'), content }} />;
}
