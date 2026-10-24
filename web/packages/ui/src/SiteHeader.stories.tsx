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
const shortcuts = ['Mobile mechanic', 'Home cleaning', 'Plumber', 'Groceries', 'Bakery', 'Butcher', 'Pharmacy']
  .map(label => ({ key: label, label, href: `/${label.toLowerCase().replace(/ /g, '-')}` }));
const menu = [
  { key: 'services', heading: 'Services', href: '/services', links: shortcuts.slice(0, 3) },
  { key: 'shop', heading: 'Shop', href: '/shop', links: shortcuts.slice(3) },
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
    shortcuts,
    end: [{ key: 'help', label: 'Help', href: '/help' }],
    menu,
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
/** "All": every category by section, in a sheet from the left. */
export const AllCategories: S = {
  args: { search },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: 'All categories' }));
    const sheet = within(document.body).getByRole('dialog', { name: 'All categories' });
    await expect(within(sheet).getByRole('navigation', { name: 'Shop' })).toBeInTheDocument();
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
/** 320 px: logo · place · account · cart, the search under them, the nav row scrolling by itself; the page never scrolls sideways. */
export const Narrow: S = {
  args: { account: signedIn, search: <SearchBar variant="header" value="" onChange={() => {}} onSubmit={() => {}} /> },
  // S-180: the phone layout comes from the window's width (media queries), not from a 320 px box, so the test sizes the
  // window itself (Vitest's browser mode); in the Storybook UI pick a 320 px viewport to see it
  play: async ({ canvasElement }) => {
    try {
      const { page } = await import('@vitest/browser/context');
      await page.viewport(320, 720);
    } catch { /* the Storybook UI: no browser control */ }
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    const header = canvasElement.querySelector('header')!;
    if (window.innerWidth > 340) return; // not at phone width (the Storybook UI on a desktop)
    await expect(header.scrollWidth).toBeLessThanOrEqual(header.clientWidth);
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth);
  },
};
