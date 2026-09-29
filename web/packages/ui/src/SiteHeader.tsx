import { useState } from 'react';
import { ShoppingBag, CaretDown } from '@phosphor-icons/react';
import { LocationPill, type GeoStatus } from './LocationPill';
import { SearchBar } from './SearchBar';
import { AccountMenu, type AccountUser } from './AccountMenu';
import { Button } from './Button';
export interface SiteHeaderProps {
  geo: { status: GeoStatus; label?: string }; onLocation: () => void;
  showSearch: boolean; query: string; onQuery: (q: string) => void; onSearch: () => void;
  cartCount: number; user?: AccountUser; activeOrders?: number;
  onNavigate: (to: string) => void; onSignIn: () => void; onCreateAccount: () => void; onNotYou: () => void; onSignOut: () => void;
}
/** Nav = Services · Shop · Food. Orders are in the account menu. */
export function SiteHeader(p: SiteHeaderProps) {
  const [open, setOpen] = useState(false);
  return (
    <header className="nav" style={{ padding: '12px 32px', flexWrap: 'wrap' }}>
      <a className="nav-brand" href="/">Northline</a>
      <LocationPill status={p.geo.status} label={p.geo.label} onClick={p.onLocation} />
      {p.showSearch && <div style={{ flex: '1 1 240px', maxWidth: 480 }}><SearchBar size="header" value={p.query} onChange={p.onQuery} onSubmit={p.onSearch} /></div>}
      <div style={{ flex: 1 }} />
      <nav style={{ display: 'flex', gap: 2 }}>{['services', 'shop', 'food'].map(s => <Button key={s} variant="ghost" onClick={() => p.onNavigate('/' + s)} style={{ color: 'var(--color-text)', textTransform: 'capitalize' }}>{s}</Button>)}</nav>
      <Button variant="secondary" icon aria-label={`Cart, ${p.cartCount} items`} onClick={() => p.onNavigate('/cart')} style={{ borderRadius: 999, position: 'relative' }}>
        <ShoppingBag weight="duotone" size={22} />
        {p.cartCount > 0 && <span style={{ position: 'absolute', top: -2, right: -2, minWidth: 18, height: 18, borderRadius: 999, background: 'var(--color-highlight)', color: 'var(--color-on-highlight)', fontSize: 11, fontWeight: 700, display: 'grid', placeItems: 'center' }}>{p.cartCount}</span>}
      </Button>
      {p.user ? (
        <div style={{ position: 'relative' }}>
          <button aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(o => !o)} style={{ display: 'flex', alignItems: 'center', gap: 6, border: 0, background: 'var(--color-accent-100)', borderRadius: 999, padding: '4px 10px 4px 4px', minHeight: 44, cursor: 'pointer', color: 'var(--color-accent-900)' }}>
            <span style={{ width: 36, height: 36, borderRadius: 999, background: 'var(--color-accent)', color: 'var(--color-on-accent)', display: 'grid', placeItems: 'center', fontWeight: 600, fontSize: 14 }}>{p.user.initials}</span><CaretDown size={13} />
          </button>
          {open && <div style={{ position: 'absolute', right: 0, top: 'calc(100% + 6px)', zIndex: 30 }}><AccountMenu user={p.user} activeOrders={p.activeOrders ?? 0} onNavigate={to => { setOpen(false); p.onNavigate(to); }} onNotYou={p.onNotYou} onSignOut={p.onSignOut} /></div>}
        </div>
      ) : (<div style={{ display: 'flex', gap: 8 }}><Button variant="ghost" onClick={p.onSignIn}>Sign in</Button><Button onClick={p.onCreateAccount}>Create account</Button></div>)}
    </header>
  );
}
