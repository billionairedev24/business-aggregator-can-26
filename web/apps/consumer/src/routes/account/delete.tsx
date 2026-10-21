import { createFileRoute } from '@tanstack/react-router';
import accountCss from '../../features/account/account.css?url';
import { DeleteAccountScreen } from '../../features/account/DeleteAccountScreen';
import { pageTitle } from '../../features/shell/messages';

/** Account › Delete account (S-159): what it affects, then S-105's erasure request. Personal: browser only. */
export const Route = createFileRoute('/account/delete')({
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'accountDelete') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: accountCss }] }),
  component: DeleteAccountScreen,
});
