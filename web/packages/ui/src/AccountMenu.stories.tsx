import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { AccountMenu } from './AccountMenu';
const meta = { title: 'Header/AccountMenu', component: AccountMenu, args: { user: { name: 'Amara Osei', email: 'amara@example.ca', initials: 'AO' }, activeOrders: 3, onNavigate: fn(), onNotYou: fn(), onSignOut: fn() } } satisfies Meta<typeof AccountMenu>;
export default meta;
type S = StoryObj<typeof meta>;
export const WithActiveOrders: S = {};
export const NoActiveOrders: S = { args: { activeOrders: 0 } };
