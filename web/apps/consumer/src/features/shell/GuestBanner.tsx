import { useRouterState } from '@tanstack/react-router';
import { SiteLink } from '@northline/ui';
import { signInHref } from '../session/api';
import { useShellT } from './messages';

/** Design 06 `signedOutBanner`: above cart, booking, food checkout, orders, account and quote for guests. */
export function GuestBanner() {
  const t = useShellT();
  const href = useRouterState({ select: s => s.location.href });
  return (
    <div className="nl-guest" role="note">
      <span className="nl-guest-text">{t('guestNote')}</span>
      <SiteLink href={signInHref(href)} className="btn btn-secondary">{t('signIn')}</SiteLink>
    </div>
  );
}
