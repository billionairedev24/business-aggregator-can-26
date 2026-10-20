import { createFileRoute } from '@tanstack/react-router';
import accountCss from '../features/account/account.css?url';
import { AccountDeletionPage } from '../features/account/DeleteAccountScreen';
import { pageTitle } from '../features/shell/messages';

/** The public page on deleting an account (S-159): the link Google Play's listing gives for account deletion. */
export const Route = createFileRoute('/account-deletion')({
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'accountDeletion') }], links: [{ rel: 'stylesheet', href: accountCss }] }),
  component: AccountDeletionPage,
});
