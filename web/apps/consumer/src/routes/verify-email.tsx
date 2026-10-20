import { useState } from 'react';
import { createFileRoute, useRouter } from '@tanstack/react-router';
import { z } from 'zod';
import { Alert, Button } from '@northline/ui';
import { appAuthorizationUrl, bffLoginUrl, EnrolSecondFactor, VerifyEmailLink, type AuthSession } from '@northline/auth-kit';
import authCss from '../features/auth/auth.css?url';
import { AuthPage } from '../features/auth/AuthPage';
import { useAuthT } from '../features/auth/messages';
import { signInHref } from '../features/session/api';
import { pageTitle } from '../features/shell/messages';

/**
 * S-154: the verification email's button. Confirms the address; the browser that created the account is signed in
 * (a second factor offered, skippable), any other one signs in with email + password.
 */
export const Route = createFileRoute('/verify-email')({
  validateSearch: z.object({ token: z.string().optional().catch(undefined) }),
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'register') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: authCss }] }),
  component: VerifyEmail,
});

function VerifyEmail() {
  const { token } = Route.useSearch();
  const t = useAuthT();
  const router = useRouter();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [done, setDone] = useState<AuthSession | null>(null);
  const finish = (s: AuthSession) => window.location.assign(appAuthorizationUrl(s.continueTo) ?? bffLoginUrl('/location'));
  const content = !token ? <Alert tone="error">{t('linkExpired')}</Alert>
    : done ? (
      <>
        <div className="nl-auth-done" role="status"><strong>{t('created')}</strong> {t('createdBody')}</div>
        <Button className="nl-auth-primary" onClick={() => finish(done)}>{t('setAddress')}</Button>
      </>
    )
    : session ? <EnrolSecondFactor email={session.user.email ?? ''} required={false} onDone={setDone} onSkip={() => setDone(session)} />
    : <VerifyEmailLink token={token} onSignedIn={s => (s.needsSecondFactor ? setSession(s) : setDone(s))}
        signInAction={email => <Button className="nl-auth-primary" onClick={() => void router.navigate({ href: `${signInHref('/location', 'sign-in')}&step=password&identifier=${encodeURIComponent(email)}` })}>{t('resetSignIn')}</Button>} />;
  return <AuthPage mode="register" search={{}} panel={{ title: t('verifyTitle'), content }} />;
}
