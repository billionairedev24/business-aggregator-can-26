import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { pending } from '../../features/shell/pending';

/** The account area (S-58, S-59): one tab per account-menu item (design 06 `acctTabs`). */
export const ACCOUNT_TABS = ['wallet', 'payments', 'profile', 'addresses', 'favourites', 'security', 'notifications', 'language', 'dietary', 'plus', 'help'] as const;
export const AccountParams = z.object({ tab: z.enum(ACCOUNT_TABS).optional().catch(undefined) });
export const Route = createFileRoute('/account/')({ validateSearch: AccountParams, ...pending('account') });
