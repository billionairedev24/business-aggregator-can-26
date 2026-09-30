import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import authCss from '../features/auth/auth.css?url';
import { AuthPage } from '../features/auth/AuthPage';
import { pageTitle } from '../features/shell/messages';

const text = z.string().optional().catch(undefined);
/** `next` = where to land (a local path; the BFF checks it again); the rest comes back from Google / Apple (S-18). */
export const AuthParams = z.object({
  next: text, step: text, identifier: text, error: text, firstName: text, lastName: text, email: text, provider: text, link: text, relay: text,
});

/** Sign in (S-62): design 06 `auth`. */
export const Route = createFileRoute('/sign-in')({
  validateSearch: AuthParams,
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'signIn') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: authCss }] }),
  component: SignIn,
});

function SignIn() {
  return <AuthPage mode="signin" search={Route.useSearch()} />;
}
