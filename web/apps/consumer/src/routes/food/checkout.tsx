import { createFileRoute } from '@tanstack/react-router';
import foodCss from '../../features/food/food.css?url';
import { CheckoutScreen } from '../../features/food/CheckoutScreen';
import { pageTitle } from '../../features/shell/messages';

/** Food checkout (S-57). Guests see the guest banner. Personal: rendered in the browser. */
export const Route = createFileRoute('/food/checkout')({
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'foodCheckout') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: foodCss }] }),
  component: CheckoutScreen,
});
