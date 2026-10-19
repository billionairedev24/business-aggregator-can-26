import type { ReactNode } from 'react';
import { CaretDown, Check } from '@phosphor-icons/react';
import { Avatar, Menu, useLocale, SkipLink } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import type { MerchantType } from '../shell/api';
import { Brand } from '../shell/StudioLayout';
import type { Onboarding } from './api';
import { useOnboardingT, type OnboardingT } from './messages';
import { STEPS, stepIndex, type Step } from './model';
import './Onboarding.css';

export const stepName = (step: Step, t: OnboardingT): string => t(`step_${step}`);

/** The rail's second line, worded for the declared type only (§ 3.10: a provider never reads the seller's copy). */
function stepSub(step: Step, type: MerchantType | undefined, t: OnboardingT): string {
  const food = type === 'kitchen';
  switch (step) {
    case 'account': return t('sub_account');
    case 'business': return t(food ? 'sub_business_kitchen' : 'sub_business');
    case 'categories': return t(food ? 'sub_categories_kitchen' : type === 'seller' ? 'sub_categories_seller' : type === 'both' ? 'sub_categories_both' : 'sub_categories');
    case 'verification': return t(food ? 'sub_verification_kitchen' : type === 'seller' ? 'sub_verification_seller' : 'sub_verification');
    case 'review': return t(food ? 'sub_review_kitchen' : 'sub_review');
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

/**
 * Onboarding chrome (redesign § 1.3 Forms, AfroTransact BO:113): the dark top bar with no site nav and no sidebar,
 * then a `240px step rail | form card` grid. The card shows "Step n of 5" and a progress bar above the step; "Finish
 * later" signs out (every step saves on Continue, and the wizard resumes where the owner left off).
 */
export function OnboardingLayout({ step, type, onboarding, onGo, children }: OnboardingLayoutProps) {
  const t = useOnboardingT();
  const signOut = useSignOut();
  const current = STEPS.indexOf(step);
  const furthest = reachable(onboarding);
  const submitted = onboarding ? onboarding.status !== 'applicant' : false;
  // the bar shows the step being filled in as reached (step 1 of 5 = a fifth), all of it once submitted
  const pct = `${Math.round((Math.min(current + 1, STEPS.length) / STEPS.length) * 100)}%`;
  return (
    <div className="nl-shell nl-ob-shell">
      <SkipLink />
      <TopBar onboarding={onboarding} />
      <div className="nl-ob">
        <nav className="nl-ob-rail" aria-labelledby="nl-ob-rail">
          <div className="nl-ob-rail-title" id="nl-ob-rail">{t('railTitle')}</div>
          <ol className="nl-ob-steps">
            {STEPS.map((s, i) => {
              const done = i < current || i < furthest || (submitted && i < STEPS.length - 1);
              const state = i === current ? 'current' : done ? 'done' : 'todo';
              return (
                <li key={s}>
                  <button type="button" className="nl-ob-step" aria-current={i === current ? 'step' : undefined} disabled={i > furthest} onClick={() => onGo(s)}>
                    <span className="nl-ob-num" data-state={state} aria-hidden>{state === 'done' ? <Check size={14} weight="bold" /> : i + 1}</span>
                    <span className="nl-ob-step-text">{stepName(s, t)}<span className="nl-ob-step-sub">{stepSub(s, type, t)}</span></span>
                    <span className="nl-sr-only">{t('stepOf', { n: i + 1, total: STEPS.length })}{done && i !== current ? ` · ${t('stepDone')}` : ''}</span>
                  </button>
                </li>
              );
            })}
          </ol>
          <p className="nl-ob-rail-note">{t('railNote')}</p>
        </nav>
        <main className="nl-ob-card" id="main" tabIndex={-1}>
          <div className="nl-ob-card-head">
            <span className="nl-ob-stepof">{t('stepOf', { n: current + 1, total: STEPS.length })}</span>
            {onboarding && step !== 'review' ? <button type="button" className="btn btn-secondary btn-sm" onClick={() => void signOut()}>{t('finishLater')}</button> : null}
          </div>
          <div className="nl-ob-progress" aria-hidden><span style={{ width: pct }} /></div>
          {children}
        </main>
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
  const tag = onboarding?.status === 'pending' ? { cls: 'tag-warning', text: t('pending') } : onboarding?.status === 'active' ? { cls: 'tag-success', text: t('approved') } : { cls: 'tag-neutral', text: t('applicant') };
  return (
    <header className="nav nl-topbar">
      <Brand label={t('studio')} />
      <span className="nl-ob-biz">{name}{onboarding?.city ? ` · ${onboarding.city}` : ''}</span>
      <span className={`tag ${tag.cls}`}>{tag.text}</span>
      <div className="nl-topbar-end">
        <Menu label={t('accountMenu')} width={260} items={[
          { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
          { kind: 'separator' },
          { label: t('signOut'), onSelect: () => void signOut() },
        ]} trigger={({ props }) => (
          <button type="button" aria-label={t('accountMenu')} {...props} className="nl-ob-account">
            <Avatar initials={user?.initials ?? '··'} />
            <span className="nl-ob-account-name">{user ? `${user.firstName} ${user.lastName}` : ''}</span>
            <CaretDown size={13} aria-hidden />
          </button>
        )} />
      </div>
    </header>
  );
}
