import type { Meta, StoryObj } from '@storybook/react-vite';
import { Input } from './Input';
const meta = { title: 'Forms/Input', component: Input, args: { label: 'Email', placeholder: 'you@example.ca' } } satisfies Meta<typeof Input>;
export default meta;
type S = StoryObj<typeof meta>;
export const Default: S = {};
export const WithError: S = { args: { error: 'Enter a valid email address.', defaultValue: 'amara@' } };
