import clsx from 'clsx';
import { useRouterState } from '@tanstack/react-router';
import { SiteLink, Skeleton, useMessageValues } from '@northline/ui';
import { useSiteConfig } from '../../lib/config';
import { signInHref, useViewer } from '../session/api';
import { useSellT } from './messages';

export type BusinessType = 'provider' | 'seller' | 'kitchen';
const TYPES: readonly BusinessType[] = ['provider', 'seller', 'kitchen'];

/** Studio onboarding for a business type (07a provider, 07b seller, 07c kitchen; `new=1` = 07d, a brand-new account). */
export const onboardingPath = (type?: BusinessType, fresh = false) => {
  const qs = new URLSearchParams({ ...(type ? { type } : {}), ...(fresh ? { new: '1' } : {}) }).toString();
  return `/onboarding${qs ? `?${qs}` : ''}`;
};

/**
 * Where "Start as …" goes. Signed in here: the Studio BFF's sign-in hand-off (`/bff/login?next=`, S-20/S-62) — the
 * auth server already has the person's session, so they land in onboarding with the same account. A guest: the
 * Studio's onboarding itself, which sends them through the Studio's sign-in and back.
 */
export function studioStart(studio: string, type: BusinessType, signedIn: boolean): string {
  const next = onboardingPath(type);
  return signedIn ? `${studio}/bff/login?next=${encodeURIComponent(next)}` : `${studio}${next}`;
}

/** 07d: a brand-new account (Studio registration, then onboarding with `new=1`). */
export const studioFresh = (studio: string, type?: BusinessType) =>
  `${studio}/register?next=${encodeURIComponent(onboardingPath(type, true))}`;

/**
 * "Sell or offer a service on Northline" (S-61, design 06 account › `sell`): three ways in — provider, seller,
 * kitchen — each into Studio onboarding (07a–07c); `?type=` (the footer's links) marks one. Who is signed in is known
 * in the browser only, so the account line starts as a skeleton.
 */
export function SellScreen({ type }: { type?: BusinessType }) {
  const t = useSellT();
  const { studioOrigin } = useSiteConfig();
  const { user, loading } = useViewer();
  const { province } = useMessageValues();
  const href = useRouterState({ select: s => s.location.href });
  const permit = province ? t('permitIn') : t('permit');
  const cards: Record<BusinessType, { title: string; body: string; verify: string; cta: string }> = {
    provider: { title: t('providerTitle'), body: t('providerBody'), verify: t('providerVerify'), cta: t('providerCta') },
    seller: { title: t('sellerTitle'), body: t('sellerBody'), verify: t('sellerVerify'), cta: t('sellerCta') },
    kitchen: { title: t('kitchenTitle'), body: t('kitchenBody'), verify: t('kitchenVerify', { permit }), cta: t('kitchenCta') },
  };

  return (
    <div className="nl-sell">
      <h1>{t('heading')}</h1>
      <p className="nl-sell-lede">{t('lede')}</p>
      <ul className="nl-sell-cards">
        {TYPES.map(key => {
          const card = cards[key];
          const chosen = key === type;
          return (
            <li key={key} className={clsx('nl-sell-card', chosen && 'nl-sell-card-chosen')} aria-labelledby={`sell-${key}`}>
              <h2 id={`sell-${key}`}>{card.title}{chosen ? <span className="nl-sr-only"> · {t('chosen')}</span> : null}</h2>
              <p>{card.body}</p>
              <p className="nl-sell-verify">{card.verify}</p>
              {loading
                ? <Skeleton height={44} radius="var(--radius-md)" style={{ marginTop: 'auto' }} />
                : <a className="btn btn-primary nl-sell-cta" href={studioStart(studioOrigin, key, !!user)}>
                    {card.cta}<span className="nl-sr-only"> {t('opensStudio')}</span>
                  </a>}
            </li>
          );
        })}
      </ul>
      <p className="nl-sell-account">
        {loading ? <Skeleton width="60%" height={16} /> : user ? (
          <>
            {t('loggedInAs')} <strong>{user.firstName} {user.lastName}</strong> {t('carryOver')}{' '}
            <a href={studioFresh(studioOrigin, type)}>{t('startFresh')}</a>
          </>
        ) : (
          <>
            {t('guestAsk')} <SiteLink href={signInHref(href)}>{t('signIn')}</SiteLink> {t('guestCarry')}{' '}
            <a href={studioFresh(studioOrigin, type)}>{t('startFresh')}</a>
          </>
        )}
      </p>
      <p className="nl-sell-footnote">{t('footnote')}</p>
    </div>
  );
}
