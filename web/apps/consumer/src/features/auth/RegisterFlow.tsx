import { useState, type FormEvent } from 'react';
import { Alert, Button, Checkbox, Field, StepBars, TextInput, useLocale } from '@northline/ui';
import {
  authApi, createAccountSchema, fieldErrors, flowError, RateLimitNotice, useRateLimit, type AuthSession, type EmailSent, PasswordField, EmailCodeStep, EnrolSecondFactor,
  MarketingConsents, useMarketingConsents,
} from '@northline/auth-kit';
import { termsPresentation } from '../../lib/legal';
import { useFrenchFirst } from '../location/regions';
import { LegalLink } from './LegalLink';
import { useAuthT, type AuthT } from './messages';
import { SocialButtons } from './Social';

type Step = 'form' | 'verify' | 'mfa' | 'done';
const PROGRESS: Record<Step, number> = { form: 0, verify: 1, mfa: 2, done: 2 };

export interface RegisterValues { fullName: string; email: string; password: string; passwordConfirm: string; phone: string; terms: boolean }
type FieldName = keyof RegisterValues;
const FIELDS: readonly FieldName[] = ['fullName', 'email', 'password', 'passwordConfirm', 'phone', 'terms'];

/** "Amara Kofi Osei" → first "Amara Kofi", last "Osei" (the auth server keeps both, validation-rules.md). */
export function splitName(fullName: string): { firstName: string; lastName: string } {
  const words = fullName.trim().split(/\s+/).filter(Boolean);
  if (words.length < 2) return { firstName: words[0] ?? '', lastName: '' };
  return { firstName: words.slice(0, -1).join(' '), lastName: words[words.length - 1]! };
}

/** Client-side check with the registration rules' exact messages (a one-word name misses the last name). */
function validate(v: RegisterValues, t: AuthT): Partial<Record<FieldName, string>> {
  const r = createAccountSchema(t).safeParse({ ...splitName(v.fullName), email: v.email, password: v.password, passwordConfirm: v.passwordConfirm, phone: v.phone, terms: v.terms });
  if (r.success) return {};
  const out: Partial<Record<FieldName, string>> = {};
  for (const issue of r.error.issues) {
    const f = String(issue.path[0]);
    const field: FieldName = f === 'firstName' || f === 'lastName' ? 'fullName' : (f as FieldName);
    out[field] ??= issue.message;
  }
  return out;
}

export interface RegisterFlowProps {
  prefill?: { firstName?: string; lastName?: string; email?: string };
  onSignIn: () => void;
  onFinished: (session: AuthSession) => void;
}

/**
 * Create account (S-154): name, email, password, optional mobile, terms → the 6-digit code from the email → a second
 * factor offered (authenticator app or passkey; "Skip for now" — consumers don't need one, S-62) → account created.
 */
export function RegisterFlow({ prefill, onSignIn, onFinished }: RegisterFlowProps) {
  const t = useAuthT();
  const { locale } = useLocale();
  const [step, setStep] = useState<Step>('form');
  const [values, setValues] = useState<RegisterValues>({ fullName: [prefill?.firstName, prefill?.lastName].filter(Boolean).join(' '), email: prefill?.email ?? '', password: '', passwordConfirm: '', phone: '', terms: false });
  const [touched, setTouched] = useState<Partial<Record<FieldName, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Partial<Record<FieldName, string>>>({});
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<EmailSent | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();
  // S-116: in a French-first place the Terms come in French first; English only when asked for (recorded)
  const frenchFirst = useFrenchFirst();
  const [englishRequested, setEnglishRequested] = useState(false);
  const presented = termsPresentation({ frenchFirst, locale, englishRequested });
  // S-157: unticked offers checkboxes (email, texts) with the server's wording — CASL express consent
  const marketing = useMarketingConsents('consumer');
  const [marketingError, setMarketingError] = useState<string>();

  const clientErrors = validate(values, t);
  const errorOf = (f: FieldName) => ((touched[f] || submitted) ? clientErrors[f] : undefined) ?? server[f];
  const attention = submitted ? FIELDS.filter(f => errorOf(f)).length : 0;
  const change = <K extends FieldName>(f: K, v: RegisterValues[K]) => {
    setValues(x => ({ ...x, [f]: v }));
    setServer(s => ({ ...s, [f]: undefined }));
    setFailure('');
  };
  const touch = (f: FieldName) => setTouched(x => ({ ...x, [f]: true }));
  const restart = () => { setStep('form'); setFailure(t('restart')); };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (Object.keys(clientErrors).length) return;
    setBusy(true); setFailure('');
    try {
      const info = await authApi.createAccount({
        ...splitName(values.fullName), email: values.email, password: values.password, passwordConfirm: values.passwordConfirm, phone: values.phone, terms: values.terms,
        termsLanguage: presented.language, termsEnglishRequested: presented.frenchFirst && presented.frenchAvailable && englishRequested,
        marketing: values.phone.trim() ? marketing.chosen() : marketing.chosen().filter(c => c.category !== 'marketing_sms'),
      }, 'consumer', locale);
      setSent(info);
      setValues(x => ({ ...x, password: '', passwordConfirm: '' }));
      setStep('verify');
      limit.clear();
    } catch (err) {
      if (limit.hold(err)) return;
      const f = fieldErrors(err, t);
      setServer({ fullName: f.firstName ?? f.lastName, phone: f.phone, email: f.email, password: f.password, passwordConfirm: f.passwordConfirm, terms: f.terms });
      setMarketingError(f.marketing);
      setFailure(flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };

  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      {step === 'form' && <p className="nl-auth-sub">{t('subRegister')}</p>}
      {failure && step !== 'form' && <Alert tone="error">{failure}</Alert>}
      {step === 'form' && (
        <form noValidate onSubmit={e => void submit(e)}>
          <div className="nl-auth-fields">
            <Field label={t('fullName')} error={errorOf('fullName')}>
              <TextInput name="name" autoComplete="name" placeholder={t('fullNamePh')} value={values.fullName}
                onBlur={() => touch('fullName')} onChange={e => change('fullName', e.target.value)} />
            </Field>
            <Field label={t('email')} error={errorOf('email')}>
              <TextInput name="email" type="email" autoComplete="email" placeholder={t('emailPh')} value={values.email}
                onBlur={() => touch('email')} onChange={e => change('email', e.target.value)} />
            </Field>
            <PasswordField label={t('passwordLabel')} hint={t('passwordHint')} autoComplete="new-password" value={values.password} error={errorOf('password')}
              onBlur={() => touch('password')} onChange={v => change('password', v)} />
            <PasswordField label={t('passwordConfirmLabel')} name="passwordConfirm" autoComplete="new-password" value={values.passwordConfirm} error={errorOf('passwordConfirm')}
              onBlur={() => touch('passwordConfirm')} onChange={v => change('passwordConfirm', v)} />
            <Field label={t('mobile')} note={t('optional')} error={errorOf('phone')}>
              <TextInput name="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder={t('mobilePh')} value={values.phone}
                onBlur={() => touch('phone')} onChange={e => change('phone', e.target.value)} />
            </Field>
          </div>
          <Checkbox className="nl-auth-terms" name="terms" checked={values.terms} onChange={c => change('terms', c)}
            label={<span>{t('termsBefore')}<LegalLink doc="terms" lang={presented.language}>{t('terms')}</LegalLink>{t('termsAnd')}<LegalLink doc="privacy" lang={presented.language}>{t('privacy')}</LegalLink>{t('termsAfter')}</span>} />
          {presented.frenchFirst && (presented.frenchAvailable
            ? <p className="nl-auth-note">
              {englishRequested ? t('termsEnglishChosen') : t('termsFrenchFirst')}{' '}
              <button type="button" className="nl-auth-link" onClick={() => setEnglishRequested(x => !x)}>{englishRequested ? t('termsFrench') : t('termsEnglish')}</button>
            </p>
            : <p className="nl-auth-note">{t('termsFrenchPending')}</p>)}
          {errorOf('terms') && <div role="alert" className="nl-error">{errorOf('terms')}</div>}
          <MarketingConsents app="consumer" consents={marketing} hasPhone={!!values.phone.trim()} error={marketingError} />
          {attention > 0 && <Alert tone="error">{t('attention', { count: attention })}</Alert>}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={busy || limit.limited} aria-busy={busy || undefined}>
            {busy ? t('creating') : t('createAccount')}
          </Button>
          <SocialButtons />
          <div className="nl-auth-switch">{t('haveAccount')} <button type="button" className="nl-auth-link" onClick={onSignIn}>{t('signIn')}</button></div>
        </form>
      )}
      {step === 'verify' && sent && (
        <EmailCodeStep sent={sent} onVerified={s => { setSession(s); setStep(s.needsSecondFactor ? 'mfa' : 'done'); }} onBack={() => setStep('form')} onRestart={restart} />
      )}
      {step === 'mfa' && session && (
        <EnrolSecondFactor email={session.user.email ?? values.email.trim()} required={false}
          onDone={s => { setSession(s); setStep('done'); }} onSkip={() => setStep('done')} />
      )}
      {step === 'done' && session && (
        <>
          <div className="nl-auth-done" role="status"><strong>{t('created')}</strong> {t('createdBody')}</div>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t('setAddress')}</Button>
        </>
      )}
    </>
  );
}
