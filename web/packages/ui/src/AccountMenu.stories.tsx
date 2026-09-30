import type { Meta, StoryObj } from '@storybook/react-vite';
import { Bell, CreditCard, Heart, Lifebuoy, MapPinLine, Receipt, ShieldCheck, SignOut, Storefront, User, Wallet } from '@phosphor-icons/react';
import { expect, fn, userEvent, within } from 'storybook/test';
import { AccountMenu, type AccountMenuSection } from './AccountMenu';

const sections: AccountMenuSection[] = [
  { heading: 'Activity', items: [
    { key: 'orders', label: 'Orders & bookings', icon: Receipt, value: '3 active', href: '/account/orders' },
    { key: 'favourites', label: 'Favourites', icon: Heart, value: '4', href: '/account?tab=favourites' },
    { key: 'wallet', label: 'Wallet & points', icon: Wallet, value: '12,480 pts', href: '/account?tab=wallet' },
  ] },
  { heading: 'Account', items: [
    { key: 'profile', label: 'Profile', icon: User, value: 'Amara Osei', href: '/account?tab=profile' },
    { key: 'addresses', label: 'Addresses & household', icon: MapPinLine, href: '/account?tab=addresses' },
    { key: 'payments', label: 'Payment methods', icon: CreditCard, value: 'Visa ··4471', href: '/account?tab=payments' },
    { key: 'security', label: 'Security & sign-in', icon: ShieldCheck, value: 'Passkey', href: '/account?tab=security' },
  ] },
  { heading: 'Preferences', items: [{ key: 'notifications', label: 'Notifications', icon: Bell, href: '/account?tab=notifications' }] },
  { heading: '', items: [
    { key: 'help', label: 'Help & cases', icon: Lifebuoy, value: '1 open', href: '/account?tab=help' },
    { key: 'sell', label: 'Sell or offer a service', icon: Storefront, href: '/sell' },
    { key: 'signout', label: 'Sign out', icon: SignOut, onSelect: fn() },
  ] },
];

const meta = {
  title: 'Site/AccountMenu',
  component: AccountMenu,
  args: {
    user: { name: 'Amara Osei', initials: 'AO', detail: 'amara@example.ca · reliability 4.9' },
    headerAction: { label: 'Add photo', href: '/account?tab=profile' },
    card: <><span><strong>12,480 pts</strong> · $124.80</span><span className="tag tag-highlight">Standard</span></>,
    sections,
  },
  decorators: [Story => <header className="nav" style={{ justifyContent: 'flex-end', padding: 12 }}><Story /></header>],
} satisfies Meta<typeof AccountMenu>;
export default meta;
type S = StoryObj<typeof meta>;

export const Closed: S = {};
export const Open: S = {
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('button', { name: 'Account menu' }));
    const first = c.getByRole('menuitem', { name: /Add photo/ });
    await expect(first).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    await expect(c.getByRole('menuitem', { name: /Orders & bookings/ })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect(c.queryByRole('menu')).toBeNull();
    await expect(c.getByRole('button', { name: 'Account menu' })).toHaveFocus();
  },
};
export const WithoutValues: S = { args: { card: undefined, headerAction: undefined, user: { name: 'Amara Osei', initials: 'AO', detail: 'amara@example.ca' } } };
