import { createFileRoute } from '@tanstack/react-router';
import ordersCss from '../../features/orders/orders.css?url';
import { OrderStatus } from '../../features/orders/OrderStatus';
import aftercareCss from '../../features/aftercare/aftercare.css?url';
import { pageTitle } from '../../features/shell/messages';

/** Order confirmed and tracking (S-52): design 06 `confirmed`, live over the order's event stream. Personal: browser only. */
export const Route = createFileRoute('/orders/$orderId')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'confirmed') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: ordersCss }, { rel: 'stylesheet', href: aftercareCss }],
  }),
  component: OrderRoute,
});

function OrderRoute() {
  const { orderId } = Route.useParams();
  return <OrderStatus orderId={orderId} />;
}
