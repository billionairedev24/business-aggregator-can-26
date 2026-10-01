import { Alert, useLocale, timeZone } from '@northline/ui';
import { useSimulateApproval, useAdvanceStep, type Onboarding } from './api';
import { checkText, isComplete } from './checks';
import { useOnboardingT, type OnboardingT } from './messages';

type State = 'done' | 'progress' | 'todo';
interface Item { text: string; state: State }

/** The review list (design 02 `reviewItems`): the checklist's state plus the steps Northline runs next. */
export function reviewItems(o: Onboarding, t: OnboardingT, locale: 'en' | 'fr'): Item[] {
  const approved = o.status === 'active';
  const items: Item[] = o.checklist
    .filter(c => c.key !== 'bank' && c.key !== 'mfa' && c.key !== 'site_visit')
    .map(c => {
      const text = checkText(c, o.type, t, locale);
      const state: State = c.status === 'verified' ? 'done' : isComplete(c) ? 'progress' : 'todo';
      const label = c.key === 'kyc' ? (state === 'done' ? t('rv_identity') : t('rv_identity_todo')) : t('rv_line', { name: text.name, state: state === 'done' ? text.done : state === 'progress' ? t('rv_submitted') : t('rv_todo') });
      return { text: label, state };
    });
  if (o.type === 'kitchen') {
    const visit = o.checklist.find(c => c.key === 'site_visit');
    if (visit) items.push({ text: `${checkText(visit, o.type, t, locale).name} · ${checkText(visit, o.type, t, locale).done}`, state: visit.status === 'verified' ? 'done' : 'progress' });
    items.push({ text: t('rv_audit'), state: 'todo' }, { text: t('rv_ts_after_visit'), state: approved ? 'done' : 'todo' });
  } else {
    items.push({ text: approved ? t('rv_ts_done') : t('rv_ts_queue'), state: approved ? 'done' : 'progress' }, { text: t('rv_welcome'), state: 'todo' });
  }
  return items;
}

export interface ReviewStepProps { onboarding: Onboarding; onNext: () => void }

/** Step 4 · Review (design 02 lines 193–202). "Simulate approval →" exists only in dev builds (api: `local` profile). */
export function ReviewStep({ onboarding: o, onNext }: ReviewStepProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const advance = useAdvanceStep(o.merchantId);
  const approve = useSimulateApproval(o.merchantId);
  const approved = o.status === 'active';
  const when = o.submittedAt ? submittedLabel(o.submittedAt, locale, t) : '';
  const next = () => advance.mutate('page', { onSuccess: onNext });

  return (
    <>
      <span className={`tag ${approved ? 'tag-accent' : 'tag-accent-2'}`}>{approved ? t('approved') : t('underReview')}</span>
      <h1 className="nl-ob-title" style={{ marginTop: 12 }}>{t('reviewTitle')}</h1>
      <p className="nl-ob-intro" style={{ marginBottom: 24 }}>{approved ? t('reviewApproved') : t('reviewLede', { when })}</p>
      <ul className="nl-ob-review">
        {reviewItems(o, t, locale).map((r, i) => <li key={i} data-state={r.state}><span className="nl-ob-dot" data-state={r.state} aria-hidden />{r.text}</li>)}
      </ul>
      {o.type === 'kitchen' ? <div className="nl-ob-food"><Alert tone="error" role="status" title={t('foodTitle')}>{t('foodBody')}</Alert></div> : null}
      {advance.isError || approve.isError ? <div style={{ marginTop: 14, maxWidth: 620 }}><Alert tone="error">{t('saveError')}</Alert></div> : null}
      <div className="nl-ob-actions" style={{ marginTop: 28 }}>
        <button type="button" className="btn btn-primary" disabled={advance.isPending} onClick={next}>{t('whileIWait')}</button>
        {import.meta.env.DEV && !approved
          ? <button type="button" className="btn btn-ghost" disabled={approve.isPending} onClick={() => approve.mutate(undefined, { onSuccess: next })}>{t('simulate')}</button>
          : null}
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
