import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import checkoutCss from '../../features/checkout/checkout.css?url';
import { CompletePage } from '../../features/checkout/CompletePage';
import { pageTitle } from '../../features/shell/messages';

/** Order placed (redesign § 2.1 `checkout/complete`): `?order=&ref=` from the checkout's Place. Personal: browser only. */
export const CompleteParams = z.object({
  order: z.string().regex(/^[A-Za-z0-9_-]{1,40}$/).optional().catch(undefined),
  ref: z.string().max(40).optional().catch(undefined),
});
export const Route = createFileRoute('/checkout/complete')({
  validateSearch: CompleteParams,
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'checkoutComplete') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: checkoutCss }],
  }),
  component: CompleteRoute,
});

function CompleteRoute() {
  const { order, ref } = Route.useSearch();
  return <CompletePage orderId={order} refCode={ref} />;
}
