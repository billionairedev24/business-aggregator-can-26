import { createFileRoute } from '@tanstack/react-router';
import authCss from '../features/auth/auth.css?url';
import { AuthPage } from '../features/auth/AuthPage';
import { pageTitle } from '../features/shell/messages';
import { AuthParams } from './sign-in';

/** Create account (S-62): design 06 `auth` with authMode new. */
export const Route = createFileRoute('/register')({
  validateSearch: AuthParams,
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'register') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: authCss }] }),
  component: Register,
});

function Register() {
  return <AuthPage mode="register" search={Route.useSearch()} />;
}
