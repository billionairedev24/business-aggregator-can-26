import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, within } from 'storybook/test';
import { BrandMark } from './BrandMark';
import { Price, ProductCard, QuantityStepper, ServiceCard, Stars } from './Commerce';

/** The consumer commerce kit (redesign § 1.3): Price, Stars, the quantity stepper, product and service cards. */
const meta = {
  title: 'Site/Commerce',
  component: ProductCard,
  args: { href: '/products/p1', name: 'Country sourdough', priceCents: 750, meta: 'Glenmore Bakery · 900 g', rating: 4.6, reviewCount: 128 },
  decorators: [Story => <div style={{ maxWidth: 960 }}><Story /></div>],
} satisfies Meta<typeof ProductCard>;
export default meta;
type S = StoryObj<typeof meta>;

export const Prices: S = {
  render: () => (
    <p style={{ display: 'flex', gap: 24, alignItems: 'flex-start', flexWrap: 'wrap' }}>
      <Price cents={750} size="sm" /><Price cents={1999} /><Price cents={4504} size="lg" compareAtCents={5200} /><Price cents={128000} size="xl" /><Price cents={8500} prefix="from" suffix="/ hr" />
    </p>
  ),
  play: async ({ canvasElement }) => { await expect(within(canvasElement).getByText('$45.04')).toBeInTheDocument(); },
};

export const Ratings: S = {
  render: () => <p style={{ display: 'grid', gap: 8 }}><Stars rating={4.6} reviewCount={128} /><Stars rating={3.5} showValue size={18} /><Stars rating={5} reviewCount={1} /></p>,
  play: async ({ canvasElement }) => { await expect(within(canvasElement).getByRole('img', { name: '4.6 out of 5 stars' })).toBeInTheDocument(); },
};

function GridDemo() {
  const [qty, setQty] = useState(0);
  return (
    <ul className="nl-card-grid" aria-label="Results">
      <li><ProductCard href="/products/p1" name="Country sourdough" priceCents={750} meta="Glenmore Bakery · 900 g" rating={4.6} reviewCount={128}
        eta={<>Get it <strong>tonight</strong></>} inCart={qty} onAdd={() => setQty(1)} onQuantity={setQty} max={6} /></li>
      <li><ProductCard href="/products/p2" name="Ribeye, AAA — 340 g, dry-aged 28 days" priceCents={1850} compareAtCents={2100} meta="Bridgeland Butcher" lowStock={2}
        badges={[{ label: 'Deal', tone: 'danger' }]} onAdd={() => {}} /></li>
      <li><ProductCard href="/products/p3" name="Baguette" priceCents={640} meta="Glenmore Bakery" soldOut /></li>
    </ul>
  );
}
export const Grid: S = {
  render: () => <GridDemo />,
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('button', { name: 'Add to cart: Country sourdough' }));
    await expect(c.getByRole('group', { name: 'Quantity of Country sourdough' })).toHaveTextContent('1 in cart');
    await userEvent.click(c.getByRole('button', { name: 'One more Country sourdough' }));
    await expect(c.getByRole('group', { name: 'Quantity of Country sourdough' })).toHaveTextContent('2 in cart');
    await expect(c.getByText('Currently unavailable')).toBeInTheDocument();
  },
};

export const Rail: S = {
  render: args => (
    <ul className="nl-card-grid nl-card-grid-rail" aria-label="Popular near you">
      {['Country sourdough', 'Seeded rye with a very long name that wraps to more than two lines on the rail', 'Croissant'].map((n, i) => (
        <li key={n}><ProductCard {...args} variant="rail" name={n} href={`/products/${i}`} /></li>
      ))}
    </ul>
  ),
};

/** No photo: the initial on linen by default, or the app's own picture (`fallback`, e.g. the consumer's illustrations). */
export const NoPhoto: S = {
  render: args => (
    <ul className="nl-card-grid nl-card-grid-rail" aria-label="Without photos">
      <li><ProductCard {...args} variant="rail" name="Strawberry jam" href="/products/j" /></li>
      <li><ProductCard {...args} variant="rail" name="Rye loaf" href="/products/r"
        fallback={<span style={{ display: 'grid', placeItems: 'center', background: 'var(--color-surface-brand)', color: 'var(--color-action-primary)', fontWeight: 700 }}>Illustration</span>} /></li>
    </ul>
  ),
  play: async ({ canvasElement }) => { await expect(within(canvasElement).getByText('Illustration')).toBeInTheDocument(); },
};

export const Services: S = {
  render: () => (
    <ul className="nl-card-grid nl-card-grid-services" aria-label="Providers">
      <li><ServiceCard href="/providers/prairie-wrench" kind="Mobile mechanic" name="Prairie Wrench" rating={4.9} reviewCount={312} place="Comes to you · 2.4 km" priceCents={8900} verified
        mark={<BrandMark name="Prairie Wrench" color="var(--color-action-primary)" size={96} />} note="Next opening tomorrow 9:00 a.m." /></li>
      <li><ServiceCard href="/providers/sable" kind="Cocktail bar" name="Sable & Soda" reviewCount={0} place="Events" priceText="Quote" /></li>
    </ul>
  ),
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole('link', { name: /Prairie Wrench/ })).toHaveAttribute('href', '/providers/prairie-wrench');
  },
};

function ChoiceDemo() {
  const [picked, setPicked] = useState('a');
  return (
    <div style={{ display: 'grid', gap: 8, maxWidth: 520 }}>
      <ServiceCard onSelect={() => setPicked('a')} selected={picked === 'a'} kind="Repair" name="Brake pads, front" priceCents={18900} fromPrice={false} place="About 90 min" />
      <ServiceCard onSelect={() => setPicked('b')} selected={picked === 'b'} kind="Diagnostic" name="Check engine light" priceCents={8900} fromPrice={false} place="About 45 min" />
    </div>
  );
}
export const ServiceChoice: S = {
  render: () => <ChoiceDemo />,
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('button', { name: /Check engine light/ }));
    await expect(c.getByRole('button', { name: /Check engine light/ })).toHaveAttribute('aria-pressed', 'true');
  },
};

export const Stepper: S = {
  render: () => {
    const Demo = () => { const [v, setV] = useState(2); return <QuantityStepper value={v} max={5} onChange={setV} name="Baguette" />; };
    return <Demo />;
  },
};
