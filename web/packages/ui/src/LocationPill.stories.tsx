import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, within } from 'storybook/test';
import { LocationPill } from './LocationPill';

const meta = { title: 'Site/LocationPill', component: LocationPill, args: { status: 'detected', label: 'Calgary', href: '/location' } } satisfies Meta<typeof LocationPill>;
export default meta;
type S = StoryObj<typeof meta>;

/** The name starts with the words on screen (WCAG 2.5.3) and says what the control does (S-170's wording). */
export const Detected: S = {
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole('link')).toHaveAccessibleName('Deliver to Calgary. Change location');
  },
};
export const Locating: S = { args: { status: 'locating', label: undefined } };
export const Fallback: S = { args: { status: 'fallback', label: 'Calgary' } };
export const Saved: S = { args: { status: 'saved', label: '1204 17 Ave SW, Calgary' } };
export const Denied: S = { args: { status: 'denied', label: undefined } };
