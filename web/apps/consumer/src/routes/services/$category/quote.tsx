import { createFileRoute, notFound } from '@tanstack/react-router';
import { z } from 'zod';
import { isNotFound } from '@northline/client';
import quotesCss from '../../../features/quotes/quotes.css?url';
import { QuoteRequest, type RequestStep } from '../../../features/quotes/QuoteRequest';
import { QuoteSkeleton } from '../../../features/quotes/QuotePage';
import { serviceCategoryQuery } from '../../../features/services/api';
import { pageTitle } from '../../../features/shell/messages';

/** `?step=job|where|who`; `?provider=<slug>` pre-ticks the provider the customer came from ("Not sure? Get quotes"). */
export const QuoteRequestParams = z.object({
  step: z.enum(['job', 'where', 'who']).optional().catch(undefined),
  provider: z.string().max(80).optional().catch(undefined),
});

/**
 * Quote request for a category (S-56): design 06 `book` in quote mode — the job, where & when, who should quote. The
 * category renders on the server; the providers covering the customer load in the browser (as the provider list).
 */
export const Route = createFileRoute('/services/$category/quote')({
  validateSearch: QuoteRequestParams,
  loader: async ({ context, params }) => {
    try {
      await context.queryClient.ensureQueryData(serviceCategoryQuery(params.category, context.locale));
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'quoteRequest') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: quotesCss }],
  }),
  pendingComponent: QuoteSkeleton,
  component: QuoteRequestRoute,
});

function QuoteRequestRoute() {
  const { category } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    <QuoteRequest slug={category} step={search.step ?? 'job'} preselect={search.provider}
      onStep={(step: RequestStep) => void navigate({ search: prev => ({ ...prev, step }) })}
      onSent={requestId => void navigate({ to: '/quotes/requests/$requestId', params: { requestId } })} />
  );
}
