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

const SUGGESTIONS = [
  { id: 's', label: 'Suggestions', items: [
    { id: 'p1', text: 'Country sourdough', highlight: [{ start: 8, length: 4 }], meta: 'Glenmore Bakery · $7.50' },
    { id: 'p2', text: 'Sourdough rye', highlight: [{ start: 0, length: 4 }], meta: 'Sidewalk Citizen · $8.50' },
    { id: 'c1', text: 'Sourdough starter kits', highlight: [{ start: 0, length: 4 }], meta: 'Shop' },
  ] },
  { id: 'r', label: 'Your recent', items: [{ id: 'r1', text: 'Recent: cinnamon buns', meta: 'searched yesterday' }] },
];
const Predicting = ({ variant, onPick, onSubmit }: { variant: 'hero' | 'header'; onPick: (id: string) => void; onSubmit: (q: string) => void }) => {
  const [v, set] = useState('');
  return (
    <div style={{ background: variant === 'hero' ? 'var(--color-accent)' : 'transparent', padding: 32, minHeight: 320, borderRadius: 'var(--radius-lg)' }}>
      <SearchBar value={v} onChange={set} onSubmit={onSubmit} variant={variant} suggestions={SUGGESTIONS} onPick={s => onPick(s.id)} />
    </div>
  );
};
/** Predictions as you type (S-48): ↓ highlights, Enter picks, Esc closes. */
export const HeaderPredictions: S = {
  args: { onPick: fn() },
  render: args => <Predicting variant="header" onSubmit={args.onSubmit} onPick={id => args.onPick?.({ id, text: id })} />,
  play: async ({ canvasElement, args }) => {
    const c = within(canvasElement);
    const field = c.getByRole('combobox', { name: 'Search' });
    await userEvent.type(field, 'sour');
    await expect(c.getByRole('listbox', { name: 'Search suggestions' })).toBeVisible();
    await userEvent.keyboard('{ArrowDown}{ArrowDown}');
    await expect(c.getByRole('option', { name: /Sourdough rye/ })).toHaveAttribute('aria-selected', 'true');
    await userEvent.keyboard('{Enter}');
    await expect(args.onPick).toHaveBeenCalledWith({ id: 'p2', text: 'p2' });
    await expect(args.onSubmit).not.toHaveBeenCalled();
  },
};
export const HeroPredictions: S = {
  args: { onPick: fn() },
  render: args => <Predicting variant="hero" onSubmit={args.onSubmit} onPick={id => args.onPick?.({ id, text: id })} />,
  play: async ({ canvasElement }) => {
    await userEvent.type(within(canvasElement).getByRole('combobox', { name: 'Search' }), 'sour');
  },
};
