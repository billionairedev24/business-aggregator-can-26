import { useState } from 'react';
import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { Alert, Button } from '@northline/ui';
import { EnrolSecondFactor, VerifyEmailLink, type AuthSession } from '@northline/auth-kit';
import { bffLoginUrl } from '../lib/auth-server';
import { SignedOutPage } from '../features/auth/SignedOutPage';
import { useAuthT } from '../features/auth/messages';
import { validateAuthSearch } from '../features/auth/routeSupport';

/**
 * S-154: the verification email's button. Confirms the address; the browser that created the account is signed in and
 * sets up its second factor here, any other one signs in with email + password (and sets it up then).
 */
export const Route = createFileRoute('/verify-email')({
  validateSearch: (raw: Record<string, unknown>) => ({ ...validateAuthSearch(raw), token: typeof raw.token === 'string' ? raw.token : undefined }),
  component: VerifyEmailRoute,
});

function VerifyEmailRoute() {
  const { token } = Route.useSearch();
  const t = useAuthT();
  const navigate = useNavigate();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [enrolled, setEnrolled] = useState<AuthSession | null>(null);
  const content = !token ? <Alert tone="error">{t('linkExpired')}</Alert>
    : enrolled ? (
      <>
        <Alert tone="info" title={t('accountCreated')} role="status">{t('accountCreatedBody', { name: `${enrolled.user.firstName} ${enrolled.user.lastName}`.trim() })}</Alert>
        <Button className="nl-auth-primary" onClick={() => window.location.assign(bffLoginUrl('/onboarding'))}>{t('continueOnboarding')}</Button>
      </>
    )
    : session ? <EnrolSecondFactor email={session.user.email ?? ''} required onDone={setEnrolled} />
    : <VerifyEmailLink token={token} onSignedIn={s => (s.needsSecondFactor === false ? setEnrolled(s) : setSession(s))}
        signInAction={email => <Button className="nl-auth-primary" onClick={() => void navigate({ to: '/sign-in', search: { step: 'password', identifier: email, next: '/onboarding' } as never })}>{t('resetSignIn')}</Button>} />;
  return <SignedOutPage mode="register" onModeChange={() => void navigate({ to: '/sign-in' })} panel={{ title: t('verifyTitle'), content }} />;
}
