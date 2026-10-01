import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import dataTableCss from '@northline/ui/DataTable.css?url';
import accountCss from '../../features/account/account.css?url';
import { AccountScreen } from '../../features/account/AccountScreen';
import { ACCOUNT_TABS } from '../../features/account/tabs';
import { pageTitle } from '../../features/shell/messages';

/** The account area (S-58, S-59, S-60): one tab per account-menu item (design 06 `acctTabs`). Personal: browser only. */
export const AccountParams = z.object({
  tab: z.enum(ACCOUNT_TABS).optional().catch(undefined),
  case: z.string().max(40).optional().catch(undefined),
});
export const Route = createFileRoute('/account/')({
  validateSearch: AccountParams,
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'account') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: accountCss }, { rel: 'stylesheet', href: dataTableCss }],
  }),
  component: AccountScreen,
});
