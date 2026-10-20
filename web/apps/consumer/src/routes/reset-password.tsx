import { useState } from 'react';
import { createFileRoute, useRouter } from '@tanstack/react-router';
import { z } from 'zod';
import { Alert, Button } from '@northline/ui';
import { ResetWithLink } from '@northline/auth-kit';
import authCss from '../features/auth/auth.css?url';
import { AuthPage } from '../features/auth/AuthPage';
import { useAuthT } from '../features/auth/messages';
import { pageTitle } from '../features/shell/messages';

/** S-154: the password-reset email's button — a new password, then sign in (a second factor is still asked). */
export const Route = createFileRoute('/reset-password')({
  validateSearch: z.object({ token: z.string().optional().catch(undefined) }),
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'signIn') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: authCss }] }),
  component: ResetPassword,
});

function ResetPassword() {
  const { token } = Route.useSearch();
  const t = useAuthT();
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);
  const content = !token ? <Alert tone="error">{t('linkExpired')}</Alert>
    : email !== null ? (
      <>
        <Alert tone="info" role="status">{t('resetDone')}</Alert>
        <Button className="nl-auth-primary" onClick={() => void router.navigate({ href: `/sign-in?step=password&identifier=${encodeURIComponent(email)}` })}>{t('resetSignIn')}</Button>
      </>
    )
    : <ResetWithLink token={token} onDone={setEmail} />;
  return <AuthPage mode="signin" search={{}} panel={{ title: t('forgotTitle'), content }} />;
}
