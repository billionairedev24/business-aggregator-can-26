import type { Meta, StoryObj } from '@storybook/react-vite';
import { ArrowsClockwise, BowlFood, ShieldCheck, ShoppingBag, Storefront, Truck, Wrench } from '@phosphor-icons/react';
import { expect, within } from 'storybook/test';
import { HomeHero } from './HomeHero';
import { MediaFallback } from './Commerce';

const pic = (name: string) => <MediaFallback name={name} />;

const meta = {
  title: 'Site/HomeHero',
  component: HomeHero,
  args: {
    title: 'Everything Maple Row needs,',
    accent: 'tonight.',
    runLabel: 'Next pooled run',
    loadingLabel: 'Loading the next pooled run…',
    departmentsLabel: 'Departments',
    trust: [
      { key: 'held', icon: <ShieldCheck size={18} />, label: 'Payment held until you confirm delivery' },
      { key: 'refund', icon: <ArrowsClockwise size={18} />, label: 'Missing items refunded automatically' },
      { key: 'shops', icon: <Storefront size={18} />, label: '380 verified shops nearby', wide: true },
    ],
    run: { icon: <Truck size={16} />, kicker: 'Tonight’s pooled run', title: 'Closes 5:20 p.m.', left: '2 h 48 min left', note: 'Mix shops for one $2.99 delivery' },
    departments: [
      { key: 'services', href: '/services', icon: <Wrench size={15} />, kicker: 'Services', title: 'Book a pro', meta: '1,240 services · book instantly', picture: pic('Services') },
      { key: 'shop', href: '/shop', icon: <ShoppingBag size={15} />, kicker: 'Shop', title: 'Shop local', meta: '380 stores · one pooled delivery', picture: pic('Shop') },
      { key: 'food', href: '/food', icon: <BowlFood size={15} />, kicker: 'Food', title: 'Order in', meta: '160 kitchens · group orders', picture: pic('Food') },
    ],
  },
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof HomeHero>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByRole('heading', { level: 1 })).toHaveTextContent('Everything Maple Row needs, tonight.');
    await expect(c.getByRole('complementary', { name: 'Next pooled run' })).toHaveTextContent('2 h 48 min left');
    await expect(c.getAllByRole('link')).toHaveLength(3);
  },
};

/** While the run loads, its card keeps its size (no layout shift) and says so to screen readers. */
export const RunLoading: Story = { args: { run: undefined } };

/** No run to count down: the run card is left out. */
export const NoRun: Story = { args: { run: null } };
