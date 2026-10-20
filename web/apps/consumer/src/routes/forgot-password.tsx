import { createFileRoute } from '@tanstack/react-router';
import authCss from '../features/auth/auth.css?url';
import { AuthPage } from '../features/auth/AuthPage';
import { pageTitle } from '../features/shell/messages';
import { AuthParams } from './sign-in';

/** S-154: "Forgot password?" (the "you already have an account" email links here). */
export const Route = createFileRoute('/forgot-password')({
  validateSearch: AuthParams,
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'signIn') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: authCss }] }),
  component: ForgotPassword,
});

function ForgotPassword() {
  return <AuthPage mode="signin" forgot search={Route.useSearch()} />;
}
