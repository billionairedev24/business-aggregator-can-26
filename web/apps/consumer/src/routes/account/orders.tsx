import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import accountCss from '../../features/account/account.css?url';
import { ORDER_TABS, ORDER_VIEWS, OrdersScreen } from '../../features/account/OrdersScreen';
import { pageTitle } from '../../features/shell/messages';

/**
 * Orders & bookings (S-58; redesign § 2.1): `?tab=bookings` and `?view=past|cases`. Reached from the account menu only,
 * never the top navigation. Personal: browser only.
 */
export const Route = createFileRoute('/account/orders')({
  validateSearch: z.object({ tab: z.enum(ORDER_TABS).optional().catch(undefined), view: z.enum(ORDER_VIEWS).optional().catch(undefined) }),
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'orders') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: accountCss }],
  }),
  component: OrdersScreen,
});
