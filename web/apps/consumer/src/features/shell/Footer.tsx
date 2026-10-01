import { SiteLink, useLocale } from '@northline/ui';
import { useShellT } from './messages';
import { useSiteConfig } from '../../lib/config';

/**
 * Design 06's footer, as a placeholder: S-63 owns the legal pages and the real footer. Privacy and Terms open the
 * verbatim documents (design 09/10) until then; "Sell / Offer / Run a kitchen" enter Studio onboarding (S-61).
 */
export function Footer() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const { legalEntity } = useSiteConfig();
  return (
    <footer className="nl-footer">
      <span>{legalEntity}</span>
      <SiteLink href="/legal/privacy.html">{t('privacy')}</SiteLink>
      <SiteLink href="/legal/terms.html">{t('terms')}</SiteLink>
      <button type="button" className="nl-footer-lang" lang={locale === 'fr' ? 'en-CA' : 'fr-CA'}
        onClick={() => setLocale(locale === 'fr' ? 'en' : 'fr')}>{t('langSwitch')}</button>
      <SiteLink href="/sell?type=seller">{t('sell')}</SiteLink>
      <SiteLink href="/sell?type=provider">{t('offer')}</SiteLink>
      <SiteLink href="/sell?type=kitchen">{t('kitchen')}</SiteLink>
    </footer>
  );
}
