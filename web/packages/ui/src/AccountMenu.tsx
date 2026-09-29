import { Receipt, FileText, Heart, Wallet, MapPinLine, CreditCard, ShieldCheck, Bell, Translate, Lifebuoy, Storefront, SignOut, type Icon } from '@phosphor-icons/react';
export interface AccountUser { name: string; email: string; initials: string }
export interface AccountMenuProps { user: AccountUser; activeOrders: number; onNavigate: (to: string) => void; onNotYou: () => void; onSignOut: () => void }
const items: [Icon, string, string][] = [
  [Receipt, 'Orders & bookings', '/account/orders'], [FileText, 'Quotes', '/account/quotes'], [Heart, 'Favourites', '/account/favourites'], [Wallet, 'Wallet & points', '/account/wallet'],
  [MapPinLine, 'Addresses & household', '/account/addresses'], [CreditCard, 'Payment methods', '/account/payments'], [ShieldCheck, 'Security & sign-in', '/account/security'],
  [Bell, 'Notifications', '/account/notifications'], [Translate, 'Language & region', '/account/language'], [Lifebuoy, 'Help & cases', '/help'], [Storefront, 'Sell or offer a service', '/business'],
];
/** Orders live here — never in the top navigation. */
export function AccountMenu({ user, activeOrders, onNavigate, onNotYou, onSignOut }: AccountMenuProps) {
  return (
    <div role="menu" style={{ width: 320, background: 'var(--color-surface)', borderRadius: 'var(--radius-lg)', boxShadow: 'var(--shadow-lg)', padding: 8 }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '10px 10px 12px' }}>
        <span style={{ width: 44, height: 44, borderRadius: 999, background: 'var(--color-accent)', color: 'var(--color-on-accent)', display: 'grid', placeItems: 'center', fontWeight: 600 }}>{user.initials}</span>
        <span style={{ display: 'flex', flexDirection: 'column' }}><strong>{user.name}</strong><span style={{ fontSize: 13, color: 'var(--color-neutral-700)' }}>{user.email}</span></span>
      </div>
      {items.map(([I, label, to], i) => (
        <button key={to} role="menuitem" onClick={() => onNavigate(to)} style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minHeight: 42, padding: '9px 10px', border: 0, borderRadius: 'var(--radius-sm)', background: i === 0 ? 'var(--color-accent-100)' : 'transparent', font: 'inherit', fontSize: 14, fontWeight: i === 0 ? 600 : 400, color: 'var(--color-text)', cursor: 'pointer', textAlign: 'left' }}>
          <I weight="duotone" size={20} color="var(--color-accent)" aria-hidden /><span style={{ flex: 1 }}>{label}</span>
          {i === 0 && activeOrders > 0 && <span style={{ fontSize: 12, fontWeight: 600, background: 'var(--color-accent)', color: 'var(--color-on-accent)', padding: '2px 8px', borderRadius: 999 }}>{activeOrders} active</span>}
        </button>
      ))}
      <div style={{ height: 1, background: 'var(--color-divider)', margin: '6px 8px' }} />
      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 10px' }}>
        <button onClick={onNotYou} className="btn btn-ghost" style={{ minHeight: 36 }}>Not you?</button>
        <button onClick={onSignOut} className="btn btn-ghost" style={{ minHeight: 36 }}><SignOut size={16} /> Sign out</button>
      </div>
    </div>
  );
}
