import { SiteLink, useLocale } from '@northline/ui';
import { useShellT } from './messages';
import { useSiteConfig } from '../../lib/config';

/**
 * The footer (E-15, design/revamp/a.html): one compact grey band — "©" and the company line (`NL_LEGAL_ENTITY`,
 * configuration, S-134; no year: a clock read at render can differ between the server and the browser) on the left,
 * then Help (for everyone, guests included: the same place on every page, WCAG 3.2.6), returns, the businesses' ways
 * in, Privacy and Terms (the verbatim design 09/10 documents, English only, so `hreflang` says so), photo credits and
 * the FR/EN switch. Audit #9: the old footer was about 400 px tall on every page.
 */
export function Footer() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const { legalEntity } = useSiteConfig();
  return (
    <footer className="nl-footer">
      <div className="nl-footer-in">
        <p className="nl-footer-legal">{t('copyright', { entity: legalEntity })}</p>
        <nav className="nl-footer-links" aria-label={t('footerHelp')}>
          <SiteLink href="/help">{t('helpLink')}</SiteLink>
          <SiteLink href="/account/orders">{t('returns')}</SiteLink>
          <SiteLink href="/sell?type=seller">{t('sell')}</SiteLink>
          <SiteLink href="/sell?type=provider">{t('offer')}</SiteLink>
          <SiteLink href="/legal/privacy.html" hrefLang="en-CA">{t('privacy')}</SiteLink>
          <SiteLink href="/legal/terms.html" hrefLang="en-CA">{t('terms')}</SiteLink>
          <SiteLink href="/credits">{t('photoCredits')}</SiteLink>
          <button type="button" className="nl-footer-lang" lang={locale === 'fr' ? 'en-CA' : 'fr-CA'}
            onClick={() => setLocale(locale === 'fr' ? 'en' : 'fr')}>{t('langSwitch')}</button>
        </nav>
      </div>
    </footer>
  );
}
