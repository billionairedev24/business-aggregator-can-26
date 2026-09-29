import { useState } from 'react';
import { Link } from '@tanstack/react-router';
import { Alert, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useCompleteCheck, useSubmitApplication, type Check, type Onboarding } from './api';
import { CheckDialog } from './CheckDialog';
import { checkText, isComplete } from './checks';
import { useOnboardingT } from './messages';

export interface VerificationStepProps { onboarding: Onboarding; onBack: () => void; onSubmitted: () => void }

/** Step 3 · Verification (design 02 lines 179–191): the per-type checklist and "Submit for review". */
export function VerificationStep({ onboarding, onBack, onSubmitted }: VerificationStepProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const complete = useCompleteCheck(onboarding.merchantId);
  const submit = useSubmitApplication(onboarding.merchantId);
  const [open, setOpen] = useState<Check | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const checks = onboarding.checklist;
  const done = checks.filter(isComplete).length;
  const applicant = onboarding.status === 'applicant';
  const dialogErrors = complete.error instanceof ValidationError ? Object.fromEntries(complete.error.errors.map(e => [e.field, e.message])) : {};
  const submitError = submit.error instanceof ValidationError ? submit.error.errors[0]?.message : submit.isError ? t('saveError') : undefined;

  const run = (c: Check) => {
    if (c.action !== 'instant') { complete.reset(); setOpen(c); return; }
    setBusy(c.id);
    complete.mutate({ id: c.id }, { onSettled: () => setBusy(null) });
  };

  return (
    <>
      <h1 className="nl-ob-title">{t('verifyTitle')}</h1>
      <p className="nl-ob-intro" style={{ maxWidth: '62ch', marginBottom: 24 }}>{t(onboarding.type === 'kitchen' ? 'verifyIntro_kitchen' : 'verifyIntro')}</p>
      <ul className="nl-ob-checks" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {checks.map(c => {
          const text = checkText(c, onboarding.type, t, locale);
          return (
            <li key={c.id} className="nl-ob-check">
              <div><div className="nl-ob-check-name">{text.name}</div><div className="nl-ob-check-desc">{text.desc}</div></div>
              {isComplete(c)
                ? <span className="tag tag-accent">{text.done}</span>
                : <button type="button" className="btn btn-secondary" disabled={busy === c.id || !applicant && onboarding.status !== 'pending'} aria-busy={busy === c.id || undefined} onClick={() => run(c)}>{text.cta}</button>}
            </li>
          );
        })}
      </ul>
      {complete.isError && !open && !(complete.error instanceof ValidationError) ? <div style={{ marginTop: 12, maxWidth: 720 }}><Alert tone="error">{t('saveError')}</Alert></div> : null}
      {submitError ? <div style={{ marginTop: 12, maxWidth: 720 }}><Alert tone="error">{submitError}</Alert></div> : null}
      <div className="nl-ob-actions">
        {applicant
          ? <button type="button" className="btn btn-primary" disabled={done < checks.length || submit.isPending} onClick={() => submit.mutate(undefined, { onSuccess: onSubmitted })}>{submit.isPending ? t('submitting') : t('submit')}</button>
          : <button type="button" className="btn btn-primary" onClick={onSubmitted}>{t('step_review')} →</button>}
        <span className="nl-ob-hint">{t('checksDone', { done, total: checks.length })}</span>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
        <Link to="/b/$merchantId/help" params={{ merchantId: onboarding.merchantId }} className="nl-small" style={{ marginLeft: 'auto' }}>{t('helpDoc')}</Link>
      </div>
      {open && (
        <CheckDialog
          merchantId={onboarding.merchantId}
          check={open}
          title={checkText(open, onboarding.type, t, locale).name}
          pending={complete.isPending}
          errors={dialogErrors}
          onClose={() => setOpen(null)}
          onSubmit={input => complete.mutate({ id: open.id, ...input }, { onSuccess: () => setOpen(null) })}
        />
      )}
    </>
  );
}
