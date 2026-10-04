import { useState, type FormEvent } from 'react';
import { Alert, Button, Checkbox, Field, OptionCard, RadioGroup, StepBars, TextInput, useLocale, codeValue } from '@northline/ui';
import { authApi, codeSchema, createPasskey, fieldErrors, firstIssue, flowError, isRestart, PasskeyError, passkeysSupported, RateLimitNotice, registerSchema, useRateLimit, type AuthSession, type TotpSetup } from '@northline/auth-kit';
import { termsPresentation } from '../../lib/legal';
import { useFrenchFirst } from '../location/regions';
import { CodeStep, type Sent } from './CodeStep';
import { LegalLink } from './LegalLink';
import { useAuthT, type AuthT } from './messages';
import { SocialButtons } from './Social';

type Step = 'form' | 'code' | 'mfa' | 'done';
const PROGRESS: Record<Step, number> = { form: 0, code: 1, mfa: 2, done: 2 };
type Method = 'passkey' | 'totp' | 'sms';

export interface RegisterValues { fullName: string; phone: string; email: string; terms: boolean }
type FieldName = keyof RegisterValues;

/** "Amara Kofi Osei" → first "Amara Kofi", last "Osei" (the auth server keeps both, validation-rules.md). */
export function splitName(fullName: string): { firstName: string; lastName: string } {
  const words = fullName.trim().split(/\s+/).filter(Boolean);
  if (words.length < 2) return { firstName: words[0] ?? '', lastName: '' };
  return { firstName: words.slice(0, -1).join(' '), lastName: words[words.length - 1]! };
}

/** Client-side check with the registration rules' exact messages (a one-word name misses the last name). */
function validate(v: RegisterValues, t: AuthT): Partial<Record<FieldName, string>> {
  const { firstName, lastName } = splitName(v.fullName);
  const r = registerSchema(t).safeParse({ firstName, lastName, phone: v.phone, email: v.email, terms: v.terms });
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
 * Create account (design 06 `auth`, authMode new): name, mobile, email, terms → 6-digit code → second factor (passkey,
 * authenticator app, or "SMS code · Backup only" = none; consumers need no second factor — S-62) → account created.
 */
export function RegisterFlow({ prefill, onSignIn, onFinished }: RegisterFlowProps) {
  const t = useAuthT();
  const { locale } = useLocale();
  const [step, setStep] = useState<Step>('form');
  const [values, setValues] = useState<RegisterValues>({ fullName: [prefill?.firstName, prefill?.lastName].filter(Boolean).join(' '), phone: '', email: prefill?.email ?? '', terms: false });
  const [touched, setTouched] = useState<Partial<Record<FieldName, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Partial<Record<FieldName, string>>>({});
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<Sent | null>(null);
  const [phoneShown, setPhoneShown] = useState('');
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();
  // S-116: in a French-first place the Terms come in French first; English only when asked for (recorded)
  const frenchFirst = useFrenchFirst();
  const [englishRequested, setEnglishRequested] = useState(false);
  const presented = termsPresentation({ frenchFirst, locale, englishRequested });

  const clientErrors = validate(values, t);
  const errorOf = (f: FieldName) => ((touched[f] || submitted) ? clientErrors[f] : undefined) ?? server[f];
  const attention = submitted ? (['fullName', 'phone', 'email', 'terms'] as const).filter(f => errorOf(f)).length : 0;
  const change = <K extends FieldName>(f: K, v: RegisterValues[K]) => {
    setValues(x => ({ ...x, [f]: v }));
    setServer(s => ({ ...s, [f]: undefined }));
    setFailure('');
  };
  const restart = () => { setStep('form'); setFailure(t('restart')); };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (Object.keys(clientErrors).length) return;
    setBusy(true); setFailure('');
    try {
      const { firstName, lastName } = splitName(values.fullName);
      const info = await authApi.register({
        firstName, lastName, phone: values.phone, email: values.email, terms: values.terms,
        termsLanguage: presented.language, termsEnglishRequested: presented.frenchFirst && presented.frenchAvailable && englishRequested,
      }, locale);
      setPhoneShown(info.phone);
      setSent({ resendAfterSeconds: info.resendAfterSeconds, channel: info.channel, at: Date.now() });
      setStep('code');
      limit.clear();
    } catch (err) {
      if (limit.hold(err)) return;
      const f = fieldErrors(err, t);
      setServer({ fullName: f.firstName ?? f.lastName, phone: f.phone, email: f.email, terms: f.terms });
      setFailure(flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };

  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      <p className="nl-auth-sub">{t('subRegister')}</p>
      {failure && step !== 'form' && <Alert tone="error">{failure}</Alert>}
      {step === 'form' && (
        <form noValidate onSubmit={e => void submit(e)}>
          <div className="nl-auth-fields">
            <Field label={t('fullName')} error={errorOf('fullName')}>
              <TextInput name="name" autoComplete="name" placeholder={t('fullNamePh')} value={values.fullName}
                onBlur={() => setTouched(x => ({ ...x, fullName: true }))} onChange={e => change('fullName', e.target.value)} />
            </Field>
            <Field label={t('mobile')} error={errorOf('phone')}>
              <TextInput name="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder={t('mobilePh')} value={values.phone}
                onBlur={() => setTouched(x => ({ ...x, phone: true }))} onChange={e => change('phone', e.target.value)} />
            </Field>
            <Field label={t('email')} error={errorOf('email')}>
              <TextInput name="email" type="email" autoComplete="email" placeholder={t('emailPh')} value={values.email}
                onBlur={() => setTouched(x => ({ ...x, email: true }))} onChange={e => change('email', e.target.value)} />
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
          {attention > 0 && <Alert tone="error">{t('attention', { count: attention })}</Alert>}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!values.phone.trim() || !values.terms || busy || limit.limited} aria-busy={busy || undefined}>
            {busy ? t('sending') : t('sendCode')}
          </Button>
          <SocialButtons />
          <div className="nl-auth-switch">{t('haveAccount')} <button type="button" className="nl-auth-link" onClick={onSignIn}>{t('signIn')}</button></div>
        </form>
      )}
      {step === 'code' && sent && (
        <CodeStep
          label={t('codeTo', { phone: phoneShown })}
          sent={sent}
          mismatch="codeWrong"
          onResend={async channel => { const info = await authApi.resend(channel, locale); return { resendAfterSeconds: info.resendAfterSeconds, channel: info.channel }; }}
          onResent={setSent}
          onVerify={async code => { await authApi.verifyPhone(code); setFailure(''); setStep('mfa'); }}
          onRestart={restart}
        />
      )}
      {step === 'mfa' && <SecondFactorStep onCreated={s => { setSession(s); setStep('done'); }} onRestart={restart} />}
      {step === 'done' && session && (
        <>
          <div className="nl-auth-done" role="status"><strong>{t('created')}</strong> {t('createdBody')}</div>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t('setAddress')}</Button>
        </>
      )}
    </>
  );
}

/** Design 06 `au.mfa`: Passkey (recommended) · Authenticator app · SMS code (backup only = no second factor). */
function SecondFactorStep({ onCreated, onRestart }: { onCreated: (s: AuthSession) => void; onRestart: () => void }) {
  const t = useAuthT();
  const [method, setMethod] = useState<Method>('passkey');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<void>) => {
    setFailure(''); setBusy(true);
    try { await fn(); } catch (err) {
      if (isRestart(err)) { onRestart(); return; }
      const fields = fieldErrors(err, t);
      setServer(fields.code ?? '');
      setFailure(fields.credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const go = () => run(async () => {
    if (method === 'passkey') {
      if (!passkeysSupported()) throw new PasskeyError('unsupported');
      const options = await authApi.passkeyRegistrationOptions();
      onCreated(await authApi.registerPasskey(await createPasskey(options)));
    } else if (method === 'totp') {
      setSetup(await authApi.totpSetup());
    } else {
      onCreated(await authApi.completeWithoutSecondFactor());
    }
  });
  const confirmTotp = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    void run(async () => onCreated(await authApi.registerTotp(code)));
  };
  const options: { key: Method; name: Parameters<typeof t>[0]; desc: Parameters<typeof t>[0] }[] = [
    { key: 'passkey', name: 'mfaPasskey', desc: 'mfaPasskeyDesc' },
    { key: 'totp', name: 'mfaTotp', desc: 'mfaTotpDesc' },
    { key: 'sms', name: 'mfaSms', desc: 'mfaSmsDesc' },
  ];
  const cta = method === 'passkey' ? t('createPasskey') : method === 'totp' ? t('scanQr') : t('continueSms');
  const codeError = (tried ? firstIssue(codeSchema(t), code) : undefined) ?? server;
  return (
    <div>
      <div className="nl-field">
        <span className="nl-label" id="nl-mfa-label">{t('mfaLabel')}</span>
        <RadioGroup className="nl-auth-options" aria-labelledby="nl-mfa-label">
          {options.map(o => (
            <OptionCard key={o.key} role="radio" aria-checked={method === o.key} selected={method === o.key} title={t(o.name)} description={t(o.desc)}
              onClick={() => { setMethod(o.key); setSetup(null); setFailure(''); }} />
          ))}
        </RadioGroup>
      </div>
      {method === 'totp' && setup ? (
        <form noValidate onSubmit={confirmTotp}>
          <div className="nl-auth-qr">
            <img src={setup.qrCode} alt={t('qrAlt')} />
            <div className="nl-auth-qr-help">
              <p>{t('qrHelp')}</p>
              <code>{t('qrKey', { secret: setup.secret })}</code>
            </div>
          </div>
          <Field label={t('code6')} error={codeError || undefined}>
            <TextInput className="nl-auth-code" name="totp" inputMode="numeric" autoComplete="one-time-code" placeholder={t('codePh')}
              value={code} onChange={e => { setCode(codeValue(e.target.value)); setServer(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <Button type="submit" className="nl-auth-primary" disabled={busy} aria-busy={busy || undefined}>{t('verifyCode')}</Button>
        </form>
      ) : (
        <>
          {failure && <Alert tone="error">{failure}</Alert>}
          <Button className="nl-auth-primary" onClick={() => void go()} disabled={busy} aria-busy={busy || undefined}>{cta}</Button>
        </>
      )}
    </div>
  );
}
