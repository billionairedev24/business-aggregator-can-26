import { useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { useStickyOffset } from './A11y';
import { List, ShoppingCartSimple } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { Logo } from './Logo';
import { Drawer } from './Overlay';
import { SiteLink } from './SiteLink';

const useT = defineMessages({
  en: {
    home: '{brand} — home', nav: 'Main', cartLabel: 'Cart', all: 'All', allLabel: 'All categories', seeAll: 'See all', seeAllOf: 'See all — {section}',
    cart: 'Cart, {count, plural, =0 {empty} one {# item} other {# items}}',
  },
  fr: {
    home: '{brand} — accueil', nav: 'Principale', cartLabel: 'Panier', all: 'Tout', allLabel: 'Toutes les catégories', seeAll: 'Tout voir', seeAllOf: 'Tout voir — {section}',
    cart: 'Panier, {count, plural, =0 {vide} one {# article} other {# articles}}',
  },
});

export interface SiteNavLink { key: string; label: string; href: string; current?: boolean; icon?: ReactNode }
/** One section of the "All" sheet: its heading (a link to the section's landing page) and its categories. */
export interface SiteMenuGroup { key: string; heading: string; href: string; links: readonly SiteNavLink[] }
export interface SiteHeaderProps {
  homeHref?: string;
  /** The location control (<LocationPill>): the place's name at every width, never the pin alone. */
  location: ReactNode;
  /** The search field (<SearchBar variant="header">): the bar's remaining width; its own full-width row on phones. */
  search?: ReactNode;
  /** Services · Shop (· Food): the sections, first in the nav row after "All". Orders are not here. */
  links: readonly SiteNavLink[];
  /** After the sections: the main departments and service categories (shortcuts to their landing pages). */
  shortcuts?: readonly SiteNavLink[];
  /** At the nav row's far end (Help). */
  end?: readonly SiteNavLink[];
  /** The "All" sheet (the row's first item): every category by section, then `menuExtra`. */
  menu?: readonly SiteMenuGroup[];
  /** More rows for the "All" sheet, after the groups (help, sell, the language switch). */
  menuExtra?: ReactNode;
  /** Absent on the sign-in pages (the logo alone). */
  cart?: { count: number; href: string };
  /** The avatar menu (<AccountMenu>), signed in or as a guest. */
  account: ReactNode;
}

/**
 * The consumer header, after AfroTransact's (owner, 2026-10-19: "too big, badly arranged"): one compact white top bar
 * — logo · location (the place, ellipsed, at every width) · search filling the middle · account · cart with its count
 * — and a slim nav row under it: "All" (the categories sheet), the sections (the current one underlined), the main
 * departments and categories, Help at the far end. The row scrolls sideways on its own (never the page). Under 900px
 * the search takes its own full-width row; on short screens the first row tucks away while scrolling down and comes
 * back on the way up. Sticky; every target ≥ 44px; nothing wider than the window at 320px.
 */
export function SiteHeader({ homeHref = '/', location, search, links, shortcuts = [], end = [], menu = [], menuExtra, cart, account }: SiteHeaderProps) {
  const t = useT();
  const ref = useRef<HTMLElement>(null);
  const [open, setOpen] = useState(false);
  // S-109: focused controls scroll clear of this sticky header (WCAG 2.4.11); the first row tucks above the search
  const tucked = useStickyOffset(ref, undefined, search ? '.nl-site-search' : undefined);
  const hasMenu = menu.length > 0 || !!menuExtra;
  const hasNav = links.length > 0 || shortcuts.length > 0 || hasMenu;
  const closeOnLink = (e: MouseEvent) => { if ((e.target as HTMLElement).closest('a')) setOpen(false); };
  const item = (l: SiteNavLink, className = 'nl-site-link') => (
    <li key={l.key}><SiteLink href={l.href} className={className} aria-current={l.current ? 'page' : undefined}>{l.icon}{l.label}</SiteLink></li>
  );
  return (
    <header ref={ref} className="nl-site-header" data-tucked={tucked || undefined}>
      <div className="nl-site-bar">
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
      {hasNav ? (
        <nav className="nl-site-subnav" aria-label={t('nav')}>
          <ul className="nl-site-nav">
            {hasMenu ? (
              <li>
                <button type="button" className="nl-site-link nl-site-all" aria-label={t('allLabel')} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
                  <List size={20} aria-hidden />{t('all')}
                </button>
              </li>
            ) : null}
            {links.map(l => item(l, 'nl-site-link nl-site-section'))}
            {shortcuts.length ? <li className="nl-site-nav-sep" aria-hidden /> : null}
            {shortcuts.map(l => item(l))}
            {end.length ? <li className="nl-site-nav-fill" aria-hidden /> : null}
            {end.map(l => item(l))}
          </ul>
        </nav>
      ) : null}
      {hasMenu ? (
        <Drawer open={open} onClose={() => setOpen(false)} side="left" width={340} title={t('allLabel')}>
          {menu.map(g => (
            <nav key={g.key} aria-label={g.heading} className="nl-site-menu-group" onClick={closeOnLink}>
              <div className="nl-site-menu-head">
                <h3 className="nl-site-menu-title">{g.heading}</h3>
                <SiteLink href={g.href} className="nl-site-menu-all" aria-label={t('seeAllOf', { section: g.heading })}>{t('seeAll')}</SiteLink>
              </div>
              <ul className="nl-site-menu-nav">
                {g.links.map(l => <li key={l.key}><SiteLink href={l.href} className="nl-site-menu-link" aria-current={l.current ? 'page' : undefined}>{l.icon}{l.label}</SiteLink></li>)}
              </ul>
            </nav>
          ))}
          {menuExtra ? <div className="nl-site-menu-extra" onClick={closeOnLink}>{menuExtra}</div> : null}
        </Drawer>
      ) : null}
    </header>
  );
}
