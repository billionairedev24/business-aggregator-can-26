import type { ReactNode } from 'react';
import { CaretDown, Check } from '@phosphor-icons/react';
import { Avatar, Menu, StatusTag, useLocale, SkipLink } from '@northline/ui';
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
 * Onboarding chrome (S-176, the wizard's own top bar): one white bar — the logo, the business being set up and its
 * state, the step indicator (numbered steps with their names, the current one solid ink, finished ones ✓ in the
 * success colour; phones: "Step n of 5 · name" over five segments), "Finish later" and the account menu. Then one
 * column: the step's title and its one group of questions, with the step's Continue bar sticky at the bottom of the
 * window. "Finish later" signs out (every step saves on Continue; the wizard resumes where the owner left off).
 */
export function OnboardingLayout({ step, type, onboarding, onGo, children }: OnboardingLayoutProps) {
  const t = useOnboardingT();
  const signOut = useSignOut();
  const current = STEPS.indexOf(step);
  const furthest = reachable(onboarding);
  const submitted = onboarding ? onboarding.status !== 'applicant' : false;
  const states = STEPS.map((_, i) => (i === current ? 'current' : i < current || i < furthest || (submitted && i < STEPS.length - 1) ? 'done' : 'todo'));
  return (
    <div className="nl-shell nl-ob-shell">
      <SkipLink />
      <TopBar onboarding={onboarding} finishLater={onboarding && step !== 'review' ? () => void signOut() : undefined}>
        <nav className="nl-ob-steps-nav" aria-label={t('railTitle')}>
          <ol className="nl-ob-steps">
            {STEPS.map((s, i) => (
              <li key={s} className="nl-ob-steps-item" data-state={states[i]}>
                <button type="button" className="nl-ob-step" aria-current={i === current ? 'step' : undefined} disabled={i > furthest} onClick={() => onGo(s)}
                  title={stepSub(s, type, t)}>
                  <span className="nl-ob-num" data-state={states[i]} aria-hidden>{states[i] === 'done' ? <Check size={13} weight="bold" /> : i + 1}</span>
                  <span className="nl-ob-step-text">{stepName(s, t)}</span>
                  <span className="nl-sr-only"> {stepSub(s, type, t)}</span>
                  <span className="nl-sr-only">{t('stepOf', { n: i + 1, total: STEPS.length })}{states[i] === 'done' ? ` · ${t('stepDone')}` : ''}</span>
                </button>
              </li>
            ))}
          </ol>
        </nav>
      </TopBar>
      <div className="nl-ob-phone-steps" aria-hidden>
        <span className="nl-ob-stepof">{t('stepOf', { n: current + 1, total: STEPS.length })} · <strong>{stepName(step, t)}</strong></span>
        <span className="nl-ob-segments">{STEPS.map((s, i) => <span key={s} data-state={states[i]} />)}</span>
      </div>
      <div className="nl-ob">
        <main className="nl-ob-card" id="main" tabIndex={-1}>
          {children}
        </main>
        <p className="nl-ob-rail-note">{t('railNote')}</p>
      </div>
    </div>
  );
}

function TopBar({ onboarding, finishLater, children }: { onboarding: Onboarding | undefined; finishLater?: () => void; children: ReactNode }) {
  const t = useOnboardingT();
  const { data: session } = useSession();
  const { locale, setLocale } = useLocale();
  const signOut = useSignOut();
  const user = session?.user;
  const name = onboarding?.business ? onboarding.displayName : t('newBusiness');
  const tag = onboarding?.status === 'pending' ? { tone: 'warning' as const, text: t('pending') } : onboarding?.status === 'active' ? { tone: 'success' as const, text: t('approved') } : { tone: 'neutral' as const, text: t('applicant') };
  return (
    <header className="nav nl-topbar nl-ob-top">
      <Brand label={t('studio')} />
      <span className="nl-ob-biz"><span className="nl-ob-biz-name">{name}{onboarding?.city ? ` · ${onboarding.city}` : ''}</span><StatusTag tone={tag.tone}>{tag.text}</StatusTag></span>
      {children}
      <div className="nl-topbar-end">
        {finishLater ? <button type="button" className="btn btn-secondary btn-sm nl-ob-later" onClick={finishLater}>{t('finishLater')}</button> : null}
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
