import { SiteLink, useLocale } from '@northline/ui';
import { useShellT } from './messages';
import { useSiteConfig } from '../../lib/config';

/**
 * Design 06's footer (S-63): the company line (`NL_LEGAL_ENTITY`, configuration — S-134), Privacy and Terms (the
 * verbatim design 09/10 documents the Studio serves too, `@northline/legal`; English only, so the links say so with
 * `hreflang`), the language switch, and "Sell on Northline / Offer a service / Run a kitchen" into Studio onboarding
 * (S-61).
 */
export function Footer() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const { legalEntity } = useSiteConfig();
  return (
    <footer className="nl-footer">
      <span>{legalEntity}</span>
      <SiteLink href="/legal/privacy.html" hrefLang="en-CA">{t('privacy')}</SiteLink>
      <SiteLink href="/legal/terms.html" hrefLang="en-CA">{t('terms')}</SiteLink>
      <button type="button" className="nl-footer-lang" lang={locale === 'fr' ? 'en-CA' : 'fr-CA'}
        onClick={() => setLocale(locale === 'fr' ? 'en' : 'fr')}>{t('langSwitch')}</button>
      <SiteLink href="/sell?type=seller">{t('sell')}</SiteLink>
      <SiteLink href="/sell?type=provider">{t('offer')}</SiteLink>
      <SiteLink href="/sell?type=kitchen">{t('kitchen')}</SiteLink>
    </footer>
  );
}
