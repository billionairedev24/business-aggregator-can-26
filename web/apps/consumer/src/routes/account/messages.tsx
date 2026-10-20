import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import accountCss from '../../features/account/account.css?url';
import { MessagesScreen } from '../../features/messages/MessagesScreen';
import { pageTitle } from '../../features/shell/messages';

/** Account › Messages (S-153): conversations with providers; `?thread=` opens one. Personal: browser only. */
export const Route = createFileRoute('/account/messages')({
  validateSearch: z.object({ thread: z.string().max(40).optional().catch(undefined) }),
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'messages') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: accountCss }],
  }),
  component: MessagesScreen,
});
