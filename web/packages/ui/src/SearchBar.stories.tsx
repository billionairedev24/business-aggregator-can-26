import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, fn, userEvent, within } from 'storybook/test';
import { SearchBar } from './SearchBar';

const meta = { title: 'Site/SearchBar', component: SearchBar, args: { value: '', onChange: fn(), onSubmit: fn() } } satisfies Meta<typeof SearchBar>;
export default meta;
type S = StoryObj<typeof meta>;

const Live = ({ variant, onSubmit }: { variant: 'hero' | 'header'; onSubmit: (q: string) => void }) => {
  const [v, set] = useState('');
  return (
    <div style={{ background: variant === 'hero' ? 'var(--color-accent)' : 'transparent', padding: 32, borderRadius: 'var(--radius-lg)' }}>
      <SearchBar value={v} onChange={set} onSubmit={onSubmit} variant={variant} placeholder="Search “sourdough”, “mobile mechanic”, “DJ”…" />
    </div>
  );
};
export const Hero: S = {
  render: args => <Live variant="hero" onSubmit={args.onSubmit} />,
  play: async ({ canvasElement, args }) => {
    const c = within(canvasElement);
    await userEvent.type(c.getByRole('searchbox', { name: 'Search' }), 'sourdough{Enter}');
    await expect(args.onSubmit).toHaveBeenCalledWith('sourdough');
  },
};
export const Header: S = { render: args => <Live variant="header" onSubmit={args.onSubmit} /> };
