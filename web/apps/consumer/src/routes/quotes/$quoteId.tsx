import { createFileRoute } from '@tanstack/react-router';
import quotesCss from '../../features/quotes/quotes.css?url';
import { QuotePage } from '../../features/quotes/QuotePage';
import { pageTitle } from '../../features/shell/messages';

/**
 * Quote received (S-56): design 06 `quote` — every line, scope, exclusions, warranty, deposit and validity, the
 * versions, and accepting by holding the deposit in escrow. The customer's own data: loaded in the browser.
 */
export const Route = createFileRoute('/quotes/$quoteId')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'quote') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: quotesCss }],
  }),
  component: QuoteRoute,
});

function QuoteRoute() {
  const { quoteId } = Route.useParams();
  return <QuotePage quoteId={quoteId} />;
}
