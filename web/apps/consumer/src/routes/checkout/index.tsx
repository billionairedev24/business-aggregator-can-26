import { createFileRoute } from '@tanstack/react-router';
import cartCss from '../../features/cart/cart.css?url';
import checkoutCss from '../../features/checkout/checkout.css?url';
import { CheckoutPage } from '../../features/checkout/CheckoutPage';
import { pageTitle } from '../../features/shell/messages';

/**
 * The 3-step checkout (redesign § 2.1 and § 3.8: the payment that left the cart): address and time, payment, review.
 * Personal: rendered in the browser (the server sends the skeleton).
 */
export const Route = createFileRoute('/checkout/')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'checkout') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: cartCss }, { rel: 'stylesheet', href: checkoutCss }],
  }),
  component: CheckoutPage,
});
