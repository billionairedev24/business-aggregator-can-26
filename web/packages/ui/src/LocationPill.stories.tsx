import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, within } from 'storybook/test';
import { LocationPill } from './LocationPill';

const meta = { title: 'Site/LocationPill', component: LocationPill, args: { status: 'detected', label: 'Calgary', href: '/location' } } satisfies Meta<typeof LocationPill>;
export default meta;
type S = StoryObj<typeof meta>;

export const Detected: S = {
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole('link')).toHaveAccessibleName(/Detected · deliver to Calgary, Detected from your device/);
  },
};
export const Locating: S = { args: { status: 'locating', label: undefined } };
export const Fallback: S = { args: { status: 'fallback', label: 'Calgary' } };
export const Saved: S = { args: { status: 'saved', label: '1204 17 Ave SW, Calgary' } };
export const Denied: S = { args: { status: 'denied', label: undefined } };
