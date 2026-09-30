import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, within } from 'storybook/test';
import { DepartmentTile, ProductTile, ShopTile, TileGrid } from './ShopTiles';

/** Design 06 Shop: product cards, shop cards (department page and landing looks) and department tiles. */
const meta = {
  title: 'Site/ShopTiles',
  component: ProductTile,
  args: { href: '/products/p1', name: 'Country sourdough', price: '$7.50', meta: 'Glenmore Bakery · 900 g' },
  decorators: [Story => <div style={{ maxWidth: 720 }}><Story /></div>],
} satisfies Meta<typeof ProductTile>;
export default meta;
type S = StoryObj<typeof meta>;

export const Product: S = {
  render: args => <TileGrid min={200} label="Products"><li><ProductTile {...args} /></li><li><ProductTile {...args} name="Seeded sourdough" price="$8.00" /></li></TileGrid>,
  play: async ({ canvasElement }) => {
    const link = within(canvasElement).getByRole('link', { name: /Country sourdough/ });
    await expect(link).toHaveAttribute('href', '/products/p1');
  },
};
export const ProductWithImageAndTag: S = { args: { imageUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=', tag: { label: 'On tonight’s run' } } };
export const ProductLongName: S = { args: { name: 'Bosch Icon 22" Beam Wiper Blade · all-season, dual rubber', price: 'from $17.50', meta: 'Prairie Wrench Parts · 14 shops' } };

export const Shops: S = {
  render: () => (
    <TileGrid min={240} label="Shops">
      <li><ShopTile href="/search?q=Glenmore" id="01J9ZD3V000000000000SHPM01" name="Glenmore Bakery" tier={{ label: 'Master' }} meta="12 products" tag={{ label: 'On tonight’s run' }} /></li>
      <li><ShopTile href="/search?q=Sidewalk" id="01J9ZD3V000000000000SHPM07" name="Sidewalk Citizen" tier={{ label: 'Trusted', tone: 'accent-2' }} meta="4 products" tag={{ label: 'Tomorrow', tone: 'neutral' }} /></li>
    </TileGrid>
  ),
};
export const ShopsOnTheRun: S = {
  render: () => (
    <TileGrid min={240} label="Shops on tonight’s run">
      <li><ShopTile look="dot" href="/shop/bakery" id="a" name="Glenmore Bakery" meta="Bakery · 12 products" tag={{ label: 'Order by 5:20 p.m.' }} /></li>
      <li><ShopTile look="dot" href="/shop/kids" id="b" name="Little Sprouts" meta="Kids · 3 products" tag={{ label: 'Tomorrow', tone: 'neutral' }} /></li>
    </TileGrid>
  ),
};
export const Departments: S = {
  render: () => (
    <TileGrid min={130} label="Shop by department">
      {['Groceries', 'Butcher', 'Bakery', 'Produce', 'Clothing', 'Pharmacy (OTC)'].map((d, i) => <li key={d}><DepartmentTile href={`/shop/${d.toLowerCase()}`} name={d} count={`${i + 2} shops`} /></li>)}
    </TileGrid>
  ),
};
