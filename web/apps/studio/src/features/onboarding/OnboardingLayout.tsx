import type { ReactNode } from 'react';
import { CaretDown } from '@phosphor-icons/react';
import { Avatar, Menu, useLocale, SkipLink } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import type { MerchantType } from '../shell/api';
import { Brand } from '../shell/StudioLayout';
import type { Onboarding } from './api';
import { useOnboardingT, type OnboardingT } from './messages';
import { STEPS, stepIndex, type Step } from './model';
import './Onboarding.css';

export function stepName(step: Step, type: MerchantType | undefined, t: OnboardingT): string {
  if (step === 'page') return t(type === 'seller' ? 'step_page_seller' : type === 'kitchen' ? 'step_page_kitchen' : 'step_page_other');
  return t(`step_${step}`);
}

function stepSub(step: Step, type: MerchantType | undefined, t: OnboardingT): string {
  const food = type === 'kitchen';
  switch (step) {
    case 'account': return t('sub_account');
    case 'business': return t(food ? 'sub_business_kitchen' : 'sub_business');
    case 'verification': return t(food ? 'sub_verification_kitchen' : type === 'seller' ? 'sub_verification_seller' : 'sub_verification');
    case 'review': return t(food ? 'sub_review_kitchen' : 'sub_review');
    case 'page': return t('sub_page');
    case 'listings': return t(food ? 'sub_listings_kitchen' : type === 'seller' ? 'sub_listings_seller' : type === 'both' ? 'sub_listings_both' : 'sub_listings_provider');
  }
}

/** How far the rail is clickable: the server's furthest step, or only Account before the applicant exists. */
export function reachable(onboarding: Onboarding | undefined): number {
  return onboarding ? stepIndex(onboarding.step) : 0;
}

export interface OnboardingLayoutProps {
  step: Step;
  type: MerchantType | undefined;
  onboarding: Onboarding | undefined;
  onGo: (step: Step) => void;
  children: ReactNode;
}

/** Onboarding chrome (design 02 lines 99–109): top bar, "Getting on Northline" step rail, content column. */
export function OnboardingLayout({ step, type, onboarding, onGo, children }: OnboardingLayoutProps) {
  const t = useOnboardingT();
  const current = STEPS.indexOf(step);
  const furthest = reachable(onboarding);
  const approved = onboarding?.status === 'active';
  return (
    <div className="nl-shell">
      <SkipLink />
      <TopBar onboarding={onboarding} />
      <div className="nl-ob">
        <aside>
          <div className="nl-ob-rail-title" id="nl-ob-rail">{t('railTitle')}</div>
          <nav aria-labelledby="nl-ob-rail">
            <ol className="nl-ob-steps">
              {STEPS.map((s, i) => {
                const done = i < current || (approved && i <= 3) || i < furthest;
                return (
                  <li key={s}>
                    <button type="button" className="nl-ob-step" aria-current={i === current ? 'step' : undefined} disabled={i > furthest} onClick={() => onGo(s)}>
                      <span className="nl-ob-num" data-state={i === current ? 'current' : done ? 'done' : 'todo'} aria-hidden>{i + 1}</span>
                      <span className="nl-ob-step-text">{stepName(s, type, t)}<span className="nl-ob-step-sub">{stepSub(s, type, t)}</span></span>
                      <span className="nl-sr-only">{t('stepOf', { n: i + 1, total: STEPS.length })}{done && i !== current ? ` · ${t('stepDone')}` : ''}</span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </nav>
          <p className="nl-ob-rail-note">{t('railNote')}</p>
        </aside>
        <main className="nl-ob-main" id="main" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}

function TopBar({ onboarding }: { onboarding: Onboarding | undefined }) {
  const t = useOnboardingT();
  const { data: session } = useSession();
  const { locale, setLocale } = useLocale();
  const signOut = useSignOut();
  const user = session?.user;
  const name = onboarding?.business ? onboarding.displayName : t('newBusiness');
  const tag = onboarding?.status === 'pending' ? { cls: 'tag-accent-2', text: t('pending') } : onboarding?.status === 'active' ? null : { cls: 'tag-neutral', text: t('applicant') };
  return (
    <header className="nav nl-topbar">
      <Brand label={t('studio')} />
      <span className="nl-ob-biz">{name}{onboarding?.city ? ` · ${onboarding.city}` : ''}</span>
      {tag ? <span className={`tag ${tag.cls}`}>{tag.text}</span> : null}
      <div className="nl-topbar-end">
        <Menu label={t('accountMenu')} width={260} items={[
          { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
          { kind: 'separator' },
          { label: t('signOut'), onSelect: () => void signOut() },
        ]} trigger={({ props }) => (
          <button type="button" aria-label={t('accountMenu')} {...props} className="nl-ob-account">
            <Avatar initials={user?.initials ?? '··'} />
            <span>{user ? `${user.firstName} ${user.lastName}` : ''}</span>
            <CaretDown size={13} />
          </button>
        )} />
      </div>
    </header>
  );
}
