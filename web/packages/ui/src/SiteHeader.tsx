import { useRef, useState, type ReactNode } from 'react';
import { useStickyOffset } from './A11y';
import { List, ShoppingCartSimple } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { Logo } from './Logo';
import { Drawer } from './Overlay';
import { SiteLink } from './SiteLink';

const useT = defineMessages({
  en: {
    home: '{brand} — home', nav: 'Main', cartLabel: 'Cart', menu: 'Menu', openMenu: 'Open the menu',
    cart: 'Cart, {count, plural, =0 {empty} one {# item} other {# items}}',
  },
  fr: {
    home: '{brand} — accueil', nav: 'Principale', cartLabel: 'Panier', menu: 'Menu', openMenu: 'Ouvrir le menu',
    cart: 'Panier, {count, plural, =0 {vide} one {# article} other {# articles}}',
  },
});

export interface SiteNavLink { key: string; label: string; href: string; current?: boolean; icon?: ReactNode }
export interface SiteHeaderProps {
  homeHref?: string;
  /** The location control (<LocationPill>): the place's name at every width, never the pin alone. */
  location: ReactNode;
  /** The search field (<SearchBar variant="header">): the bar's remaining width; its own full-width row on phones. */
  search?: ReactNode;
  /** Services · Shop (· Food): simple tabs under the bar, in the phone menu below 900px. Orders are not here. */
  links: readonly SiteNavLink[];
  /** More rows for the phone menu, after the links (help, sell, the language switch). */
  menuExtra?: ReactNode;
  /** Absent on the sign-in pages (the logo alone). */
  cart?: { count: number; href: string };
  /** The avatar menu (<AccountMenu>), signed in or as a guest. */
  account: ReactNode;
}

/**
 * The consumer header — one white bar (the owner's light direction, 2026-10-19): logo · location (the place, ellipsed,
 * at every width) · search · account · cart with its count; the sections as quiet tabs under it, with the current one
 * underlined in the primary colour. Under 900px the search takes its own full-width row and the sections move into a
 * menu sheet (the button before the logo). Sticky; no horizontal page scroll at 320px; every target ≥ 44px.
 */
export function SiteHeader({ homeHref = '/', location, search, links, menuExtra, cart, account }: SiteHeaderProps) {
  const t = useT();
  const ref = useRef<HTMLElement>(null);
  const [menu, setMenu] = useState(false);
  useStickyOffset(ref); // S-109: focused controls scroll clear of this sticky header (WCAG 2.4.11)
  const hasMenu = links.length > 0 || !!menuExtra;
  return (
    <header ref={ref} className="nl-site-header">
      <div className="nl-site-bar">
        {hasMenu ? (
          <button type="button" className="nl-site-item nl-site-menu" aria-label={t('openMenu')} aria-expanded={menu} onClick={() => setMenu(true)}>
            <List size={24} aria-hidden />
          </button>
        ) : null}
        <SiteLink href={homeHref} className="nl-site-item nl-site-brand" aria-label={t('home')}><Logo size={32} /></SiteLink>
        {location ? <div className="nl-site-location">{location}</div> : null}
        {search ? <div className="nl-site-search">{search}</div> : <div className="nl-site-spacer" />}
        {account ? <div className="nl-site-account">{account}</div> : null}
        {cart ? (
          <SiteLink href={cart.href} className="nl-site-item nl-site-cart" aria-label={t('cart', { count: cart.count })}>
            <span className="nl-site-cart-icon">
              <ShoppingCartSimple size={26} aria-hidden />
              {cart.count > 0 && <span className="nl-site-cart-count" aria-hidden>{cart.count > 99 ? '99+' : cart.count}</span>}
            </span>
            <span className="nl-site-cart-label" aria-hidden>{t('cartLabel')}</span>
          </SiteLink>
        ) : null}
      </div>
      {links.length ? (
        <nav className="nl-site-subnav" aria-label={t('nav')}>
          <div className="nl-site-nav">
            {links.map(l => <SiteLink key={l.key} href={l.href} className="nl-site-link" aria-current={l.current ? 'page' : undefined}>{l.icon}{l.label}</SiteLink>)}
          </div>
        </nav>
      ) : null}
      {hasMenu ? (
        <Drawer open={menu} onClose={() => setMenu(false)} side="left" width={320} title={<Logo size={28} />}>
          <nav aria-label={t('nav')} className="nl-site-menu-nav" onClick={e => { if ((e.target as HTMLElement).closest('a')) setMenu(false); }}>
            {links.map(l => <SiteLink key={l.key} href={l.href} className="nl-site-menu-link" aria-current={l.current ? 'page' : undefined}>{l.icon}{l.label}</SiteLink>)}
          </nav>
          {menuExtra ? <div className="nl-site-menu-extra" onClick={e => { if ((e.target as HTMLElement).closest('a')) setMenu(false); }}>{menuExtra}</div> : null}
        </Drawer>
      ) : null}
    </header>
  );
}
