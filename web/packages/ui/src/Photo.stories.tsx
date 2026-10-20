import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, within } from 'storybook/test';
import { Photo } from './Photo';
import { BAKERY_PHOTO } from './Photo.fixtures';

const meta = {
  title: 'Core/Photo',
  component: Photo,
  args: { photo: BAKERY_PHOTO, crop: 'card', ratio: true, sizes: '320px' },
  decorators: [Story => <div style={{ width: 320 }}><Story /></div>],
} satisfies Meta<typeof Photo>;
export default meta;
type S = StoryObj<typeof meta>;

/** A library photo: its own description as the alternative text, the 4:3 crop's AVIF/WebP/JPEG sets. */
export const Card: S = {
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole('img', { name: /sourdough/ })).toHaveAttribute('loading', 'lazy');
  },
};
export const SquareTile: S = { args: { crop: 'square' } };
export const WideHero: S = { args: { crop: 'wide', priority: true } };
/** Next to a heading that names it: hidden from screen readers. */
export const Decorative: S = { args: { decorative: true } };
/** A slot without a photo yet: the fallback (the consumer site passes its drawn picture). */
export const NoPhotoYet: S = {
  args: { photo: null, fallback: <span style={{ display: 'block', aspectRatio: '4 / 3', background: 'var(--color-surface-sunken)' }} aria-hidden /> },
};
