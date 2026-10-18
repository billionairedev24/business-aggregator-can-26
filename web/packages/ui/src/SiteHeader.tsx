import { useRef, type ReactNode } from 'react';
import { useStickyOffset } from './A11y';
import { ShoppingCartSimple } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { Logo } from './Logo';
import { SiteLink } from './SiteLink';

const useT = defineMessages({
  en: {
    home: '{brand} — home', nav: 'Main', cartLabel: 'Cart',
    cart: 'Cart, {count, plural, =0 {empty} one {# item} other {# items}}',
  },
  fr: {
    home: '{brand} — accueil', nav: 'Principale', cartLabel: 'Panier',
    cart: 'Panier, {count, plural, =0 {vide} one {# article} other {# articles}}',
  },
});

export interface SiteNavLink { key: string; label: string; href: string; current?: boolean; icon?: ReactNode }
export interface SiteHeaderProps {
  homeHref?: string;
  /** The "Deliver to" button (<LocationPill>). */
  location: ReactNode;
  /** The search field (<SearchBar variant="header">): on every page, taking the row's remaining width. */
  search?: ReactNode;
  /** The department bar: Services · Shop · Food first (landing pages, never search results). Orders are not here. */
  links: readonly SiteNavLink[];
  /** Absent on the sign-in pages (the logo alone). */
  cart?: { count: number; href: string };
  /** The avatar menu (<AccountMenu>), signed in or as a guest. */
  account: ReactNode;
}

/**
 * The consumer header — AfroTransact's two bars (redesign § 1.3, Shop:27–76). Bar 1, on the dark chrome: logo ·
 * "Deliver to" · search (the remaining width) · account avatar · cart with its count. Bar 2, the department bar:
 * Services / Shop / Food, one row that scrolls sideways. Sticky; under 900px the search takes its own row; no
 * horizontal page scroll at 320px. The language switch lives in the footer and the account menu.
 */
export function SiteHeader({ homeHref = '/', location, search, links, cart, account }: SiteHeaderProps) {
  const t = useT();
  const ref = useRef<HTMLElement>(null);
  useStickyOffset(ref); // S-109: focused controls scroll clear of this sticky header (WCAG 2.4.11)
  return (
    <header ref={ref} className="nl-site-header">
      <div className="nl-site-bar">
        <SiteLink href={homeHref} className="nl-site-item nl-site-brand" aria-label={t('home')}><Logo size={32} onDark /></SiteLink>
        {location}
        {search ? <div className="nl-site-search">{search}</div> : <div className="nl-site-spacer" />}
        {account}
        {cart ? (
          <SiteLink href={cart.href} className="nl-site-item nl-site-cart" aria-label={t('cart', { count: cart.count })}>
            <span className="nl-site-cart-icon">
              <ShoppingCartSimple size={30} aria-hidden />
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
    </header>
  );
}
