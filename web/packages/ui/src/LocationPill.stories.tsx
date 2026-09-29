import type { Meta, StoryObj } from '@storybook/react-vite';
import { LocationPill } from './LocationPill';
const meta = { title: 'Header/LocationPill', component: LocationPill, args: { status: 'detected', label: 'Beltline, Calgary' } } satisfies Meta<typeof LocationPill>;
export default meta;
type S = StoryObj<typeof meta>;
export const Detected: S = {};
export const Locating: S = { args: { status: 'locating' } };
export const Saved: S = { args: { status: 'saved' } };
export const Denied: S = { args: { status: 'denied' } };
