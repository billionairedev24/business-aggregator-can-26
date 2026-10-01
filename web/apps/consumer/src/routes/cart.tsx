import { createFileRoute } from '@tanstack/react-router';
import shopCss from '../features/shop/shop.css?url';
import cartCss from '../features/cart/cart.css?url';
import { CartPage } from '../features/cart/CartPage';
import { pageTitle } from '../features/shell/messages';

/**
 * Cart and checkout (S-51): multi-shop, Stripe Payment Element. Personal: rendered in the browser (the server sends
 * the skeleton). Guests see the guest banner and their cart.
 */
export const Route = createFileRoute('/cart')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'cart') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: shopCss }, { rel: 'stylesheet', href: cartCss }],
  }),
  component: CartPage,
});
