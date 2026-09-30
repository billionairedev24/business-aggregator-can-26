import { createFileRoute } from '@tanstack/react-router';
import quotesCss from '../../../features/quotes/quotes.css?url';
import { QuoteCompare } from '../../../features/quotes/QuoteCompare';
import { pageTitle } from '../../../features/shell/messages';

/** Compare the quotes of one request (S-56): design 06 `book` quote mode, "N quotes received". */
export const Route = createFileRoute('/quotes/requests/$requestId')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'quoteCompare') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: quotesCss }],
  }),
  component: CompareRoute,
});

function CompareRoute() {
  const { requestId } = Route.useParams();
  return <QuoteCompare requestId={requestId} />;
}
