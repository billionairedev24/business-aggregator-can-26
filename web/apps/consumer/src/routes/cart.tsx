import { createFileRoute } from '@tanstack/react-router';
import cartCss from '../features/cart/cart.css?url';
import { CartPage } from '../features/cart/CartPage';
import { pageTitle } from '../features/shell/messages';

/**
 * The cart (S-51; redesign § 1.3): multi-shop lines and "Proceed to checkout" — the payment moved to /checkout (§ 3.8).
 * Personal: rendered in the browser (the server sends the skeleton). Guests see their cart and sign in to check out.
 */
export const Route = createFileRoute('/cart')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'cart') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: cartCss }],
  }),
  component: CartPage,
});
