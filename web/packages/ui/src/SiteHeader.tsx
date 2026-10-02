import { useRef, type ReactNode } from 'react';
import { useStickyOffset } from './A11y';
import { ShoppingBag } from '@phosphor-icons/react';
import { defineMessages, langTag, type Locale } from './i18n';
import { SiteLink } from './SiteLink';

const useT = defineMessages({
  en: {
    home: 'Northline — home', nav: 'Main', language: 'Switch language', otherLanguage: 'Français',
    cart: 'Cart, {count, plural, =0 {empty} one {# item} other {# items}}',
  },
  fr: {
    home: 'Northline — accueil', nav: 'Principale', language: 'Changer de langue', otherLanguage: 'English',
    cart: 'Panier, {count, plural, =0 {vide} one {# article} other {# articles}}',
  },
});

export interface SiteNavLink { key: string; label: string; href: string; current?: boolean }
export interface SiteHeaderProps {
  homeHref?: string;
  /** The location pill (<LocationPill>). */
  location: ReactNode;
  /** The header search field — off the home page only (design 06). */
  search?: ReactNode;
  /** Services · Shop · Food: landing pages, never search results. Orders are not here (account menu). */
  links: readonly SiteNavLink[];
  locale: Locale;
  onToggleLocale: () => void;
  cart: { count: number; href: string };
  /** Signed in: <AccountMenu>; signed out: Sign in + Create account. */
  account: ReactNode;
}

/**
 * The consumer header (design 06): brand · location pill · [search, off-home] · Services / Shop / Food · FR/EN ·
 * cart (count) · account. Sticky; wraps onto more rows on narrow screens (no horizontal scroll at 320 px).
 */
export function SiteHeader({ homeHref = '/', location, search, links, locale, onToggleLocale, cart, account }: SiteHeaderProps) {
  const t = useT();
  const ref = useRef<HTMLElement>(null);
  useStickyOffset(ref); // S-109: focused controls scroll clear of this sticky header (WCAG 2.4.11)
  return (
    <header ref={ref} className="nav nl-site-header">
      <SiteLink href={homeHref} className="nav-brand nl-site-brand" aria-label={t('home')}>Northline</SiteLink>
      {location}
      {search && <div className="nl-site-search">{search}</div>}
      <div className="nl-site-spacer" />
      <nav className="nl-site-nav" aria-label={t('nav')}>
        {links.map(l => <SiteLink key={l.key} href={l.href} className="nl-site-link" aria-current={l.current ? 'page' : undefined}>{l.label}</SiteLink>)}
      </nav>
      {/* S-109: the name starts with the visible "EN"/"FR" (WCAG 2.5.3) and the other language's name is marked up in that
          language (3.1.2): "EN — Switch language: Français" with lang="fr-CA" on "Français". */}
      <button type="button" className="nl-site-lang" onClick={onToggleLocale} title={t('otherLanguage')}>
        {locale === 'fr' ? 'FR' : 'EN'}<span className="nl-sr-only"> — {t('language')}{locale === 'fr' ? ' : ' : ': '}</span><span className="nl-sr-only" lang={langTag(locale === 'fr' ? 'en' : 'fr')}>{t('otherLanguage')}</span>
      </button>
      <SiteLink href={cart.href} className="nl-site-cart" aria-label={t('cart', { count: cart.count })}>
        <ShoppingBag weight="duotone" size={22} aria-hidden />
        {cart.count > 0 && <span className="nl-site-cart-count" aria-hidden>{cart.count > 99 ? '99+' : cart.count}</span>}
      </SiteLink>
      {account}
    </header>
  );
}
