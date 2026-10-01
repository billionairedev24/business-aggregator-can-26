import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import dataTableCss from '@northline/ui/DataTable.css?url';
import accountCss from '../../features/account/account.css?url';
import { ORDER_VIEWS, OrdersScreen } from '../../features/account/OrdersScreen';
import { pageTitle } from '../../features/shell/messages';

/** Orders & bookings (S-58) — reached from the account menu only, never the top navigation. Personal: browser only. */
export const Route = createFileRoute('/account/orders')({
  validateSearch: z.object({ view: z.enum(ORDER_VIEWS).optional().catch(undefined) }),
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'orders') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: accountCss }, { rel: 'stylesheet', href: dataTableCss }],
  }),
  component: OrdersScreen,
});
