import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { SearchBar } from './SearchBar';
const meta = { title: 'Search/SearchBar', component: SearchBar } satisfies Meta<typeof SearchBar>;
export default meta;
const Live = (p: { size: 'hero' | 'header' }) => { const [v, set] = useState(''); return <div style={{ background: 'var(--color-accent)', padding: 32, borderRadius: 18 }}><SearchBar value={v} onChange={set} onSubmit={() => {}} size={p.size} /></div>; };
export const Hero: StoryObj = { render: () => <Live size="hero" /> };
export const Header: StoryObj = { render: () => <Live size="header" /> };
