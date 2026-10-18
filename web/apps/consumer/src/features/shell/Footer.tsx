import { SiteLink, useLocale } from '@northline/ui';
import { useShellT } from './messages';
import { useSiteConfig } from '../../lib/config';

/**
 * The footer (redesign § 1.3, AfroTransact Shop:945; S-63): on the dark chrome, link groups — help and the policies
 * (Privacy and Terms are the verbatim design 09/10 documents the Studio serves too, `@northline/legal`; English only,
 * so the links say so with `hreflang`), Help for everyone, guests included (S-145, WCAG 3.2.6: the same place on every
 * page), the businesses' way in ("Sell on …", "Offer a service", "Run a kitchen" → Studio onboarding, S-61) — the
 * language switch, and the company line (`NL_LEGAL_ENTITY`, configuration — S-134).
 */
export function Footer() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const { legalEntity } = useSiteConfig();
  return (
    <footer className="nl-footer">
      <div className="nl-footer-cols">
        <div className="nl-footer-col">
          <p id="nl-footer-help" className="nl-footer-head">{t('footerHelp')}</p>
          <div className="nl-footer-links" role="group" aria-labelledby="nl-footer-help">
            <SiteLink href="/help">{t('helpLink')}</SiteLink>
            <SiteLink href="/legal/privacy.html" hrefLang="en-CA">{t('privacy')}</SiteLink>
            <SiteLink href="/legal/terms.html" hrefLang="en-CA">{t('terms')}</SiteLink>
          </div>
        </div>
        <div className="nl-footer-col">
          <p id="nl-footer-business" className="nl-footer-head">{t('footerBusiness')}</p>
          <div className="nl-footer-links" role="group" aria-labelledby="nl-footer-business">
            <SiteLink href="/sell?type=seller">{t('sell')}</SiteLink>
            <SiteLink href="/sell?type=provider">{t('offer')}</SiteLink>
            <SiteLink href="/sell?type=kitchen">{t('kitchen')}</SiteLink>
          </div>
        </div>
        <div className="nl-footer-col">
          <p id="nl-footer-language" className="nl-footer-head">{t('footerLanguage')}</p>
          <button type="button" className="nl-footer-lang" lang={locale === 'fr' ? 'en-CA' : 'fr-CA'}
            onClick={() => setLocale(locale === 'fr' ? 'en' : 'fr')}>{t('langSwitch')}</button>
        </div>
      </div>
      <p className="nl-footer-legal">{legalEntity}</p>
    </footer>
  );
}
