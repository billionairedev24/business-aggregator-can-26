import { ArrowUp, Globe, Translate } from '@phosphor-icons/react';
import { Logo, SiteLink, useLocale } from '@northline/ui';
import { useShellT } from './messages';
import { useSiteConfig } from '../../lib/config';
import { useRenderNow } from '../../lib/renderClock';
import { DEALS_HREF } from './Header';
import { countryName } from '../location/countries';
import { useDeliveryLocation } from '../location/useDeliveryLocation';

type Key = Parameters<ReturnType<typeof useShellT>>[0];
interface FooterLink { key: Key; href: string; legal?: boolean }

/**
 * The footer's four columns (S-191, Amazon's footer pattern in the brand layer): every link is a real route or a section
 * of the help centre. The legal documents are English only (design 09/10, verbatim), so their links say `hreflang`.
 */
const COLUMNS: readonly { key: Key; links: readonly FooterLink[] }[] = [
  { key: 'colKnow', links: [
    { key: 'howItWorks', href: '/help' },
    { key: 'shopLocal', href: '/shop' },
    { key: 'bookPro', href: '/services' },
    { key: 'orderIn', href: '/food' },
    { key: 'deals', href: DEALS_HREF },
  ] },
  { key: 'colEarn', links: [
    { key: 'sell', href: '/sell?type=seller' },
    { key: 'offer', href: '/sell?type=provider' },
    { key: 'kitchen', href: '/sell?type=kitchen' },
    { key: 'waysIn', href: '/sell' },
  ] },
  { key: 'colPay', links: [
    { key: 'paymentHeld', href: '/help#help-pay' },
    { key: 'paymentMethods', href: '/account?tab=payments' },
    { key: 'accessibility', href: '/help#help-a11y' },
    { key: 'photoCredits', href: '/credits' },
  ] },
  { key: 'colHelp', links: [
    { key: 'yourAccount', href: '/account' },
    { key: 'yourOrders', href: '/account/orders' },
    { key: 'yourBookings', href: '/account/orders?tab=bookings' },
    { key: 'returns', href: '/account/orders' },
    { key: 'deliveryPlace', href: '/location' },
    { key: 'helpLink', href: '/help' },
  ] },
];
const LEGAL: readonly FooterLink[] = [
  { key: 'terms', href: '/legal/terms.html', legal: true },
  { key: 'privacy', href: '/legal/privacy.html', legal: true },
  { key: 'adsNotice', href: '/legal/privacy.html#cookies', legal: true },
];

/**
 * The footer (S-191, after Amazon's: the owner found the one-line band "so dry"): a "Back to top" bar, four link columns
 * (Get to know us · Make money with us · Payments and policies · Let us help you), a row with the logo, the FR/EN switch
 * and "language · country" (S-192: the visitor's country — chosen, else detected, else NL_SITE_COUNTRY — opening the
 * location dialog; no country or currency in code), then the legal strip (conditions of use, privacy, the cookies and ads notice, "© year company"). The
 * year is the render clock's (lib/renderClock: the server and the browser's first render agree, no hydration error);
 * the company is configuration (`NL_LEGAL_ENTITY`, S-134). Help is here for everyone, guests included (WCAG 3.2.6). On
 * phones the columns pair up two by two and the rows stack, every link a 44 px target. Colours are the footer and
 * strip roles only (the brand layer's chrome), so a palette change carries through.
 */
export function Footer() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const { legalEntity, country: siteCountry } = useSiteConfig();
  const year = useRenderNow().getFullYear();
  const { location, visitor, chooser } = useDeliveryLocation();
  const code = (location.status !== 'locating' ? location.country : undefined) ?? visitor?.country ?? siteCountry ?? undefined;
  const country = code ? countryName(code, locale) : t('regionNoCountry');
  const language = t('languageName');
  const link = (l: FooterLink) => (
    <li key={l.key}><SiteLink href={l.href} hrefLang={l.legal ? 'en-CA' : undefined}>{t(l.key)}</SiteLink></li>
  );
  const toTop = () => {
    const reduce = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
    document.getElementById('main')?.focus({ preventScroll: true });
  };
  return (
    <footer className="nl-footer">
      <button type="button" className="nl-footer-top" onClick={toTop}><ArrowUp size={16} weight="bold" aria-hidden />{t('backToTop')}</button>
      <div className="nl-footer-cols">
        {COLUMNS.map(c => (
          <nav key={c.key} className="nl-footer-col" aria-labelledby={`footer-${c.key}`}>
            <h2 id={`footer-${c.key}`} className="nl-footer-head">{t(c.key)}</h2>
            <ul>{c.links.map(link)}</ul>
          </nav>
        ))}
      </div>
      <div className="nl-footer-row">
        <SiteLink href="/" className="nl-footer-brand" aria-label={t('homeLink')}><Logo size={28} className="nl-footer-logo" /></SiteLink>
        <button type="button" className="nl-footer-chip nl-footer-lang" lang={locale === 'fr' ? 'en-CA' : 'fr-CA'}
          onClick={() => setLocale(locale === 'fr' ? 'en' : 'fr')}><Translate size={16} aria-hidden />{t('langSwitch')}</button>
        <button type="button" className="nl-footer-chip nl-footer-region" aria-haspopup="dialog" aria-label={code ? t('regionChange', { language, country }) : undefined} onClick={chooser.show}>
          <Globe size={16} aria-hidden /><span aria-hidden={code ? true : undefined}>{t('regionLabel', { language, country })}</span>
        </button>
      </div>
      <div className="nl-footer-legal">
        <nav aria-label={t('footerLegal')}><ul>{LEGAL.map(link)}</ul></nav>
        <p className="nl-footer-copy">{t('copyright', { year, entity: legalEntity })}</p>
      </div>
    </footer>
  );
}
