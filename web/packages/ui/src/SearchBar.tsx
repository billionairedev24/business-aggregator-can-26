import { MagnifyingGlass } from '@phosphor-icons/react';
import { Button } from './Button';
export type Scope = 'all' | 'services' | 'shop' | 'food';
export interface SearchBarProps { value: string; onChange: (v: string) => void; onSubmit: () => void; size?: 'hero' | 'header'; placeholder?: string }
export function SearchBar({ value, onChange, onSubmit, size = 'hero', placeholder = 'Plumber, sourdough, pho for four…' }: SearchBarProps) {
  const hero = size === 'hero';
  return (
    <form role="search" onSubmit={e => { e.preventDefault(); onSubmit(); }} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--color-surface)', borderRadius: 999, padding: hero ? 6 : 2, boxShadow: hero ? 'none' : 'inset 0 0 0 1px var(--color-neutral-300)' }}>
      <MagnifyingGlass weight="duotone" size={hero ? 22 : 18} color="var(--color-accent)" style={{ marginLeft: 12 }} aria-hidden />
      <input aria-label="Search" value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} style={{ flex: 1, border: 0, background: 'transparent', font: 'inherit', fontSize: hero ? 17 : 15, minHeight: hero ? 48 : 40, outline: 'none', color: 'var(--color-text)' }} />
      {hero && <Button variant="highlight" type="submit" style={{ borderRadius: 999, minHeight: 48, padding: '0 26px' }}>Search</Button>}
    </form>
  );
}
