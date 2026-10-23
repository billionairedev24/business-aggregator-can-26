import { Fragment, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { useStickyOffset } from './A11y';
import { List, ShoppingCartSimple, Tag } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { Logo } from './Logo';
import { Drawer } from './Overlay';
import { SiteLink } from './SiteLink';

const useT = defineMessages({
  en: {
    home: '{brand} — home', nav: 'Main', cartLabel: 'Cart', all: 'All', allLabel: 'All categories', menu: 'Menu', seeAll: 'See all', seeAllOf: 'See all — {section}',
    cart: 'Cart, {count, plural, =0 {empty} one {# item} other {# items}}',
  },
  fr: {
    home: '{brand} — accueil', nav: 'Principale', cartLabel: 'Panier', all: 'Tout', allLabel: 'Toutes les catégories', menu: 'Menu', seeAll: 'Tout voir', seeAllOf: 'Tout voir — {section}',
    cart: 'Panier, {count, plural, =0 {vide} one {# article} other {# articles}}',
  },
});

export interface SiteNavLink {
  key: string;
  label: string;
  href: string;
  current?: boolean;
  icon?: ReactNode;
  /** Shortcuts of one group sit together; a hairline separates the groups (shop departments | service categories). */
  group?: string;
  /** `deal`: the commerce colour with a tag icon ("Today's deals"); never a button colour. */
  tone?: 'deal';
}
/** One section of the "All" sheet: its heading (a link to the section's landing page) and its categories. */
export interface SiteMenuGroup { key: string; heading: string; href: string; links: readonly SiteNavLink[] }
export interface SiteHeaderProps {
  homeHref?: string;
  /** The location control (<LocationPill>): "Deliver to" over the place; on phones its own line under the search. */
  location: ReactNode;
  /** The search field (<SearchBar variant="header" scope>): the bar's remaining width; its own full-width row on phones. */
  search?: ReactNode;
  /** Services · Shop · Food: the sections, first in the department strip after "All". Orders are not here. */
  links: readonly SiteNavLink[];
  /** After the sections: the main departments and service categories (shortcuts to their landing pages), by `group`. */
  shortcuts?: readonly SiteNavLink[];
  /** At the strip's right end ("Today's deals", "Sell on …"). */
  end?: readonly SiteNavLink[];
  /** The "All" sheet (the strip's first item, and the menu button on phones): every category by section, then `menuExtra`. */
  menu?: readonly SiteMenuGroup[];
  /** More rows for the "All" sheet, after the groups (help, sell, the language switch). */
  menuExtra?: ReactNode;
  /** Absent on the sign-in pages (the logo alone). */
  cart?: { count: number; href: string };
  /** The account menu (<AccountMenu greeting>), signed in or as a guest. */
  account: ReactNode;
}

/**
 * The consumer header (E-15 A · Plainsight, design/revamp/a.html): a white search-led bar — logo · deliver-to · the
 * search with its department scope · "Hello, … / Account & orders" · cart with its count in the commerce colour — then
 * the grey department strip: "All" (the categories sheet), the sections (the current one underlined in ink), the
 * departments and service categories in groups, "Today's deals" and "Sell on …" at the right end. The strip scrolls
 * sideways on its own (never the page). Under 900px the bar is compact — the menu button, the logo, the account icon,
 * the cart — with the search and then the place on their own rows. Sticky; on short screens the first row tucks away
 * while scrolling down and comes back on the way up. Every target ≥ 44px; nothing wider than the window at 320px.
 */
export function SiteHeader({ homeHref = '/', location, search, links, shortcuts = [], end = [], menu = [], menuExtra, cart, account }: SiteHeaderProps) {
  const t = useT();
  const ref = useRef<HTMLElement>(null);
  const [open, setOpen] = useState(false);
  // S-109: focused controls scroll clear of this sticky header (WCAG 2.4.11); the first row tucks above the search
  const tucked = useStickyOffset(ref, undefined, search ? '.nl-site-search' : undefined);
  const hasMenu = menu.length > 0 || !!menuExtra;
  const hasNav = links.length > 0 || shortcuts.length > 0 || end.length > 0 || hasMenu;
  const closeOnLink = (e: MouseEvent) => { if ((e.target as HTMLElement).closest('a')) setOpen(false); };
  const item = (l: SiteNavLink, className = 'nl-site-link') => (
    <li key={l.key}>
      <SiteLink href={l.href} className={clsx(className, l.tone === 'deal' && 'nl-site-deal')} aria-current={l.current ? 'page' : undefined}>
        {l.tone === 'deal' && !l.icon ? <Tag size={16} aria-hidden /> : l.icon}{l.label}
      </SiteLink>
    </li>
  );
  return (
    <header ref={ref} className="nl-site-header" data-tucked={tucked || undefined}>
      <div className="nl-site-bar">
        {hasMenu ? (
          <button type="button" className="nl-site-item nl-site-menu-btn" aria-label={t('menu')} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
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
              <ShoppingCartSimple size={30} aria-hidden />
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
                  <List size={18} aria-hidden />{t('all')}
                </button>
              </li>
            ) : null}
            {links.map(l => item(l, 'nl-site-link nl-site-section'))}
            {shortcuts.map((l, i) => (
              <Fragment key={l.key}>
                {i === 0 || shortcuts[i - 1]!.group !== l.group ? <li className="nl-site-nav-sep" aria-hidden /> : null}
                {item(l)}
              </Fragment>
            ))}
            {end.length ? <li className="nl-site-nav-fill" aria-hidden /> : null}
            {end.map(l => item(l, 'nl-site-link nl-site-end'))}
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

/**
 * A full-width promo line under the header (E-15 "delivery cut-off strip"): the promo tint, an icon, the message (its
 * lead in bold), and a figure at the right end (the time left, tabular). Only shown where there is data to say.
 */
export function PromoStrip({ icon, children, end, label }: { icon?: ReactNode; children: ReactNode; end?: ReactNode; label?: string }) {
  return (
    <aside className="nl-promo" aria-label={label}>
      <div className="nl-promo-in">
        {icon ? <span className="nl-promo-icon" aria-hidden>{icon}</span> : null}
        <p className="nl-promo-text">{children}</p>
        {end ? <span className="nl-promo-end">{end}</span> : null}
      </div>
    </aside>
  );
}
