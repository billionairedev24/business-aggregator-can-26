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
    // S-192: the panel draws in a portal on <body>, above the header and its strip
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(c.getByRole('button', { name: 'Account menu' }));
    const first = page.getByRole('menuitem', { name: /Add photo/ });
    await expect(first).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    await expect(page.getByRole('menuitem', { name: /Orders & bookings/ })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect(page.queryByRole('menu')).toBeNull();
    await expect(c.getByRole('button', { name: 'Account menu' })).toHaveFocus();
  },
};
/** S-192, Amazon's flyout for a guest: Sign in, "New customer? Start here.", then two columns. */
export const GuestColumns: S = {
  args: {
    user: undefined, headerAction: undefined, card: undefined, layout: 'columns',
    greeting: { line1: 'Hello, sign in', line2: 'Account & orders' },
    signIn: { label: 'Sign in', href: '/sign-in', prompt: 'New customer?', start: { label: 'Start here.', href: '/register' } },
    sections: [
      { heading: 'Your lists', items: [{ key: 'favourites', label: 'Saved', icon: Heart, href: '/account?tab=favourites' }] },
      { heading: 'Your account', items: [{ key: 'orders', label: 'Orders', icon: Receipt, href: '/account/orders' }, { key: 'help', label: 'Help', icon: Lifebuoy, href: '/help' }] },
    ],
  },
  play: async ({ canvasElement }) => {
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(within(canvasElement).getByRole('button', { name: /Account & orders/ }));
    await expect(page.getByRole('menuitem', { name: 'Sign in' })).toHaveFocus();
    await expect(page.getAllByRole('group').map(g => g.getAttribute('aria-label'))).toEqual(['Your lists', 'Your account']);
  },
};
export const WithoutValues: S = { args: { card: undefined, headerAction: undefined, user: { name: 'Amara Osei', initials: 'AO', detail: 'amara@example.ca' } } };
