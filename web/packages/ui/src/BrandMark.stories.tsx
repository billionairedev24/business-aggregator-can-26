import type { Meta, StoryObj } from '@storybook/react-vite';
import { BrandMark } from './BrandMark';
const meta = { title: 'Site/BrandMark', component: BrandMark, args: { name: 'Prairie Wrench', color: '#2f5d3a' } } satisfies Meta<typeof BrandMark>;
export default meta;
type S = StoryObj<typeof meta>;
export const Initial: S = {};
export const Small: S = { args: { size: 44, name: 'Sable & Soda', color: '#5b2a5e' } };
export const Hero: S = { args: { size: 72, name: 'Bow Valley Cleaners', color: '#006786' } };
