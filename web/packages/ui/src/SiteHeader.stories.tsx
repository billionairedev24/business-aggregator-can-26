import type { Meta, StoryObj } from '@storybook/react-vite';
import { Receipt, SignIn, SignOut, UserPlus } from '@phosphor-icons/react';
import { expect, userEvent, within } from 'storybook/test';
import { AccountMenu } from './AccountMenu';
import { I18nProvider } from './i18n';
import { LocationPill } from './LocationPill';
import { SearchBar } from './SearchBar';
import { SiteHeader } from './SiteHeader';

const links = [
  { key: 'services', label: 'Services', href: '/services' },
  { key: 'shop', label: 'Shop', href: '/shop' },
  { key: 'food', label: 'Food', href: '/food' },
];
const signedOut = (
  <AccountMenu sections={[{ items: [{ key: 'in', label: 'Sign in', icon: SignIn, href: '/sign-in' }, { key: 'up', label: 'Create account', icon: UserPlus, href: '/register' }] }]} />
);
const signedIn = (
  <AccountMenu user={{ name: 'Amara Osei', initials: 'AO', detail: 'amara@example.ca' }} sections={[
    { heading: 'Activity', items: [{ key: 'orders', label: 'Orders & bookings', icon: Receipt, value: '3 active', href: '/account/orders' }] },
    { heading: '', items: [{ key: 'signout', label: 'Sign out', icon: SignOut, onSelect: () => {} }] },
  ]} />
);

const meta = {
  title: 'Site/SiteHeader',
  component: SiteHeader,
  args: {
    location: <LocationPill status="detected" label="Calgary" href="/location" />,
    links,
    cart: { count: 3, href: '/cart' },
    account: signedOut,
  },
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof SiteHeader>;
export default meta;
type S = StoryObj<typeof meta>;

const search = <SearchBar variant="header" value="" onChange={() => {}} onSubmit={() => {}} placeholder="Search “sourdough”, “mobile mechanic”, “DJ”…" />;

/** A guest: the grey avatar opens "Welcome, guest" with Sign in and Create account. */
export const SignedOut: S = {
  args: { search },
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByRole('navigation', { name: 'Main' })).not.toHaveTextContent(/Orders/);
    await expect(c.getByRole('link', { name: 'Cart, 3 items' })).toBeInTheDocument();
    await userEvent.click(c.getByRole('button', { name: 'Account menu' }));
    await expect(c.getByRole('menu')).toHaveTextContent('Welcome, guest');
  },
};
export const SignedIn: S = {
  args: { account: signedIn, search, links: links.map(l => ({ ...l, current: l.key === 'shop' })) },
};
export const EmptyCartLocating: S = { args: { cart: { count: 0, href: '/cart' }, location: <LocationPill status="locating" href="/location" /> } };
export const French: S = {
  args: { links: [{ key: 'services', label: 'Services', href: '/services' }, { key: 'shop', label: 'Boutique', href: '/shop' }, { key: 'food', label: 'Restaurants', href: '/food' }] },
  decorators: [Story => <I18nProvider initial="fr"><Story /></I18nProvider>],
};
/** 320 px: the header wraps onto more rows; nothing scrolls sideways. */
export const Narrow: S = {
  args: { account: signedIn, search: <SearchBar variant="header" value="" onChange={() => {}} onSubmit={() => {}} /> },
  decorators: [Story => <div style={{ width: 320, overflow: 'hidden' }}><Story /></div>],
  play: async ({ canvasElement }) => {
    const header = canvasElement.querySelector('header')!;
    await expect(header.scrollWidth).toBeLessThanOrEqual(header.clientWidth);
  },
};
