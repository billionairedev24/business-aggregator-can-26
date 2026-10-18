import { CheckCircle, Circle, HourglassMedium } from '@phosphor-icons/react';
import { Alert, useLocale, timeZone } from '@northline/ui';
import { useSignOut } from '../../lib/session';
import { useAdvanceStep, useSimulateApproval, type Onboarding } from './api';
import { checkText, isComplete } from './checks';
import { useOnboardingT, type OnboardingT } from './messages';

export type GateState = 'done' | 'progress' | 'todo';
export interface GateItem { label: string; note: string; state: GateState; stateText: string }

/** The gate's rows (BO:235): every check the owner did, then what {brand} runs next. Worded for the declared type. */
export function reviewItems(o: Onboarding, t: OnboardingT, locale: 'en' | 'fr'): GateItem[] {
  const approved = o.status === 'active';
  const items: GateItem[] = o.checklist
    .filter(c => c.key !== 'bank' && c.key !== 'mfa' && c.key !== 'site_visit')
    .map(c => {
      const text = checkText(c, o.type, t, locale);
      const state: GateState = c.status === 'verified' ? 'done' : isComplete(c) ? 'progress' : 'todo';
      return { label: text.name, note: text.desc, state, stateText: state === 'done' ? text.done || t('rv_passed') : state === 'progress' ? t('rv_submitted') : t('rv_todo') };
    });
  if (o.type === 'kitchen') {
    const visit = o.checklist.find(c => c.key === 'site_visit');
    if (visit) { const v = checkText(visit, o.type, t, locale); items.push({ label: v.name, note: v.desc, state: visit.status === 'verified' ? 'done' : 'progress', stateText: v.done }); }
    items.push(
      { label: t('rv_audit_label'), note: t('rv_audit'), state: 'todo', stateText: t('rv_later') },
      { label: t('rv_ts_label'), note: t('rv_ts_after_visit'), state: approved ? 'done' : 'todo', stateText: approved ? t('rv_passed') : t('rv_later') },
    );
  } else {
    items.push({ label: t('rv_ts_label'), note: t('rv_ts_note'), state: approved ? 'done' : 'progress', stateText: approved ? t('rv_passed') : t('rv_in_queue') });
  }
  return items;
}

export interface ReviewStepProps { onboarding: Onboarding; onOpenStudio: () => void }

const ICON = { done: CheckCircle, progress: HourglassMedium, todo: Circle } as const;

/**
 * Step 5 · Review = the approval gate (redesign § 3.10, AfroTransact BO:235): the application with each check's state,
 * and why the Studio stays locked until {brand}'s trust & safety team approves. Once approved, "Open your Studio" —
 * the public page and the first listings are set up there. "Simulate approval" exists only in dev builds (api:
 * `local` profile).
 */
export function ReviewStep({ onboarding: o, onOpenStudio }: ReviewStepProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const signOut = useSignOut();
  const advance = useAdvanceStep(o.merchantId);
  const approve = useSimulateApproval(o.merchantId);
  const approved = o.status === 'active';
  const when = o.submittedAt ? submittedLabel(o.submittedAt, locale, t) : '';
  const open = () => advance.mutate('done', { onSuccess: onOpenStudio });
  const ref = o.merchantId.slice(-6);
  const portal = t(o.type === 'kitchen' ? 'portal_kitchen' : 'portal_other');

  return (
    <>
      <h1 className="nl-ob-title">{approved ? t('gateApprovedTitle') : t('reviewTitle')}</h1>
      <p className="nl-ob-intro">{approved ? t('reviewApproved') : t('reviewLede', { when })}</p>
      <div className="nl-gate">
        <section className="nl-gate-card" aria-labelledby="nl-gate-app">
          <div className="nl-gate-head">
            <h2 className="nl-gate-h2" id="nl-gate-app">{t('gateApplication', { ref })}</h2>
            <span className={`tag ${approved ? 'tag-success' : 'tag-warning'}`}>{approved ? t('approved') : t('underReview')}</span>
          </div>
          <ul className="nl-gate-steps">
            {reviewItems(o, t, locale).map((r, i) => {
              const Icon = ICON[r.state];
              return (
                <li key={i} className="nl-gate-step" data-state={r.state}>
                  <Icon className="nl-gate-icon" weight={r.state === 'done' ? 'fill' : 'regular'} aria-hidden />
                  <div><div className="nl-gate-label">{r.label}</div>{r.note ? <div className="nl-gate-note">{r.note}</div> : null}</div>
                  <span className="nl-gate-state">{r.stateText}</span>
                </li>
              );
            })}
          </ul>
          {advance.isError || approve.isError ? <Alert tone="error">{t('saveError')}</Alert> : null}
          <div className="nl-gate-actions">
            {approved ? <button type="button" className="btn btn-primary" disabled={advance.isPending} onClick={open}>{t('openStudio')}</button> : null}
            <button type="button" className="btn btn-secondary" onClick={() => void signOut()}>{t('signOutGate')}</button>
          </div>
        </section>
        <div className="nl-gate-side">
          {approved ? (
            <section className="nl-gate-card" aria-labelledby="nl-gate-next">
              <h2 className="nl-gate-h2" id="nl-gate-next">{t('gateNextTitle')}</h2>
              <ol className="nl-gate-next">
                <li>{t(o.type === 'seller' ? 'gateNextPage_seller' : o.type === 'kitchen' ? 'gateNextPage_kitchen' : 'gateNextPage')}</li>
                <li>{t(o.type === 'seller' ? 'gateNextListings_seller' : o.type === 'kitchen' ? 'gateNextListings_kitchen' : o.type === 'both' ? 'gateNextListings_both' : 'gateNextListings')}</li>
              </ol>
            </section>
          ) : (
            <section className="nl-gate-card" aria-labelledby="nl-gate-why">
              <h2 className="nl-gate-h2" id="nl-gate-why">{t('gateWhyTitle', { portal })}</h2>
              <p>{t('gateWhyBody')}</p>
            </section>
          )}
          {o.type === 'kitchen' && !approved ? <Alert tone="error" role="status" title={t('foodTitle')}>{t('foodBody')}</Alert> : null}
          {import.meta.env.DEV && !approved ? (
            <section className="nl-gate-card" aria-labelledby="nl-gate-demo">
              <h2 className="nl-gate-h2" id="nl-gate-demo">{t('gateDemoTitle')}</h2>
              <p>{t('gateDemoBody')}</p>
              <div><button type="button" className="btn btn-secondary btn-sm" disabled={approve.isPending} onClick={() => approve.mutate()}>{t('simulate')}</button></div>
            </section>
          ) : null}
        </div>
      </div>
    </>
  );
}

function submittedLabel(iso: string, locale: 'en' | 'fr', t: OnboardingT): string {
  const tz = timeZone();
  const d = new Date(iso);
  const day = (x: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(x);
  const fmt = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: tz, ...o }).format(d);
  return day(d) === day(new Date()) ? t('today', { time: fmt({ hour: 'numeric', minute: '2-digit' }) }) : fmt({ month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}
