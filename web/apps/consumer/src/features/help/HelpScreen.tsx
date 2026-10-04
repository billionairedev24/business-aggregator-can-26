import { SiteLink } from '@northline/ui';
import { useSiteConfig } from '../../lib/config';
import { signInHref, useViewer } from '../session/api';
import { useHelpT } from './messages';

const CASES = '/account?tab=help';

/**
 * Help (S-145, WCAG 3.2.6): linked from the footer of every consumer page. Who is signed in is known in the browser
 * only, so the page is written for a guest first and the account links appear once the session answers. The support
 * mailbox is configuration (`NL_SUPPORT_EMAIL`); without it, contact goes through Help & cases.
 */
export function HelpScreen() {
  const t = useHelpT();
  const { user } = useViewer();
  const { supportEmail } = useSiteConfig();
  const signedIn = !!user;
  return (
    <div className="nl-page nl-help">
      <h1>{t('heading')}</h1>
      <p className="nl-help-lede">{t('lede')}</p>
      <section aria-labelledby="help-problem">
        <h2 id="help-problem">{t('problemTitle')}</h2>
        <p>{signedIn ? t('problemSignedIn') : t('problemGuest')}</p>
        <p className="nl-help-links">
          {signedIn
            ? <><SiteLink href="/account/orders">{t('toOrders')}</SiteLink><SiteLink href={CASES}>{t('toCases')}</SiteLink></>
            : <SiteLink href={signInHref(CASES)}>{t('signIn')}</SiteLink>}
        </p>
      </section>
      <section aria-labelledby="help-guest">
        <h2 id="help-guest">{t('guestTitle')}</h2>
        <p>{t('guestBody')}</p>
      </section>
      <section aria-labelledby="help-pay">
        <h2 id="help-pay">{t('payTitle')}</h2>
        <p>{t('payBody')}</p>
      </section>
      <section aria-labelledby="help-a11y">
        <h2 id="help-a11y">{t('a11yTitle')}</h2>
        <p>{t('a11yBody')}</p>
      </section>
      <section aria-labelledby="help-contact">
        <h2 id="help-contact">{t('contactTitle')}</h2>
        <p>{signedIn ? t('contactSignedIn') : t('contactGuest')}{supportEmail ? <> {t('contactEmail', { email: supportEmail })}</> : null}</p>
        <p className="nl-help-links">
          <SiteLink href={signedIn ? CASES : signInHref(CASES)}>{t('toCases')}</SiteLink>
          {supportEmail ? <a href={`mailto:${supportEmail}`}>{supportEmail}</a> : null}
        </p>
      </section>
      <section aria-labelledby="help-business">
        <h2 id="help-business">{t('businessTitle')}</h2>
        <p>{t('businessBody')}</p>
        <p className="nl-help-links"><SiteLink href="/sell">{t('business')}</SiteLink></p>
      </section>
    </div>
  );
}
